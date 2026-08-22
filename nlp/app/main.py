"""FastAPI tokenizer service.

Called only by the Spring import worker, never by the browser and never on the
read path. Owns no data and knows nothing about users or texts.
"""

import logging
import os
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from .models import TokenizeRequest, TokenizeResponse
from .tokenizer import load_dictionary, tokenize

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("kotanoba.nlp")

# Guardrail, not a scaling limit: a runaway paste should fail fast with a clear
# error rather than pinning the worker. Roughly a long news article.
MAX_TEXT_CHARS = int(os.getenv("MAX_TEXT_CHARS", "200000"))

# Deployed as a normal (public-URL) free web service rather than a network-
# isolated private one — see design.md's deployment notes for why. This is
# the substitute for network isolation: unset (the default, e.g. local dev
# and Compose) means no check at all, matching claude.md's "never on the
# read path, called only by the import worker" model where nothing public
# should be able to reach this anyway. Set only in the deployed environment,
# where the URL genuinely is public and this is what stands in for that.
INTERNAL_API_KEY = os.getenv("INTERNAL_API_KEY")


def require_internal_api_key(x_internal_api_key: str = Header(default="")):
    if INTERNAL_API_KEY and x_internal_api_key != INTERNAL_API_KEY:
        raise HTTPException(status_code=401, detail="missing or invalid X-Internal-Api-Key")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load the dictionary before accepting traffic, so the container is not
    # marked healthy while the first request would still block for seconds.
    load_dictionary()
    yield


app = FastAPI(
    title="Kotanoba NLP",
    version="0.1.0",
    description="Japanese tokenization for Kotanoba. Stateless, no database.",
    lifespan=lifespan,
)


@app.middleware("http")
async def correlation_id_middleware(request: Request, call_next):
    """Echo Spring's correlation id back and onto our log lines, so a single
    import can be traced across both runtimes (claude.md, engineering standards)."""
    correlation_id = request.headers.get("X-Correlation-Id", "-")
    started = time.perf_counter()
    response = await call_next(request)
    elapsed_ms = (time.perf_counter() - started) * 1000
    logger.info(
        "%s %s -> %s in %.1fms [correlation_id=%s]",
        request.method,
        request.url.path,
        response.status_code,
        elapsed_ms,
        correlation_id,
    )
    response.headers["X-Correlation-Id"] = correlation_id
    return response


@app.get("/health", summary="Liveness/readiness probe", operation_id="health")
def health():
    """Used by the Compose healthcheck and as the signal behind Resilience4j's
    circuit breaker on the Spring side."""
    return {"status": "ok"}


@app.post(
    "/tokenize",
    response_model=TokenizeResponse,
    summary="Tokenize Japanese text",
    # Explicit operation_id: without one, FastAPI derives it from the function
    # name + path + method, which is what the generated Java client's method
    # names come from too. Left implicit, the generated method would be named
    # something like tokenizeEndpointTokenizePost. This is the actual contract
    # the Java client is generated against — see decision #1 in claude.md on
    # contract drift being a compile error.
    operation_id="tokenize",
    dependencies=[Depends(require_internal_api_key)],
)
def tokenize_endpoint(
    request: TokenizeRequest,
    x_correlation_id: str = Header(default="-"),
):
    if len(request.text) > MAX_TEXT_CHARS:
        return JSONResponse(
            status_code=413,
            content={
                "detail": (
                    f"text exceeds {MAX_TEXT_CHARS} characters "
                    f"(got {len(request.text)})"
                )
            },
        )

    started = time.perf_counter()
    result = tokenize(request.text, request.mode)
    elapsed_ms = (time.perf_counter() - started) * 1000

    logger.info(
        "tokenized chars=%d tokens=%d words=%d lemmas=%d mode=%s in %.1fms "
        "[correlation_id=%s]",
        len(request.text),
        result.token_count,
        result.word_count,
        result.distinct_lemma_count,
        request.mode.value,
        elapsed_ms,
        x_correlation_id,
    )
    return result
