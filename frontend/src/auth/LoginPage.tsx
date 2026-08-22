import { useState, type FormEvent } from "react";
import { useAuth } from "./AuthContext";

export function LoginPage() {
  const { login, register } = useAuth();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      if (mode === "login") {
        await login(email, password);
      } else {
        await register(email, password);
      }
    } catch {
      // Backend deliberately returns the same generic message for "wrong
      // password" and "no such account" (login) to avoid confirming which
      // emails have accounts — mirrored here rather than trying to be more
      // specific than the API itself is willing to be.
      setError(mode === "login" ? "Invalid email or password." : "Could not register — email may already be in use.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <h1>Kotanoba</h1>
      <form onSubmit={handleSubmit} className="auth-form">
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={8}
          />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={submitting}>
          {mode === "login" ? "Log in" : "Register"}
        </button>
      </form>
      <button
        type="button"
        className="link-button"
        onClick={() => {
          setMode(mode === "login" ? "register" : "login");
          setError(null);
        }}
      >
        {mode === "login" ? "Need an account? Register" : "Already have an account? Log in"}
      </button>
    </main>
  );
}
