#!/usr/bin/env bash
# Regenerate backend/src/main/resources/openapi/nlp-api.json from the FastAPI
# app's schema. Run this after any change to nlp/app/models.py or main.py.
#
# The backend's Java client is generated from the checked-in file, not a live
# call to the NLP service, so the build never depends on it being up.
set -euo pipefail
cd "$(dirname "$0")/../nlp"
.venv/bin/python - <<'PYEOF'
import json
from app.main import app
schema = app.openapi()
with open("../backend/src/main/resources/openapi/nlp-api.json", "w") as f:
    json.dump(schema, f, indent=2, ensure_ascii=False)
print("wrote backend/src/main/resources/openapi/nlp-api.json")
PYEOF
