import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link, Navigate, Route, BrowserRouter as Router, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { LoginPage } from "./auth/LoginPage";
import { ImportPage } from "./texts/ImportPage";
import { LibraryPage } from "./texts/LibraryPage";
import { ReaderPage } from "./texts/ReaderPage";
import { StatisticsPage } from "./vocabulary/StatisticsPage";
import { VocabularyPage } from "./vocabulary/VocabularyPage";

const queryClient = new QueryClient();

function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />;
}

function Header() {
  const { logout } = useAuth();
  return (
    <header className="app-header">
      <Link to="/" className="app-title">
        Kotanoba
      </Link>
      <nav className="app-nav">
        <Link to="/vocabulary">Vocabulary</Link>
        <Link to="/stats">Statistics</Link>
        <button type="button" className="link-button" onClick={logout}>
          Log out
        </button>
      </nav>
    </header>
  );
}

// Definitions (design.md §13/§18) come from JMdict (EDRDG, CC BY-SA 4.0) and
// example sentences from Tatoeba (via manythings.org/anki, CC BY 2.0 FR) —
// both licenses require attribution, and neither was shown anywhere before.
function Footer() {
  return (
    <footer className="app-footer">
      Definitions from JMdict/EDRDG (CC BY-SA 4.0). Example sentences from the{" "}
      <a href="https://tatoeba.org" target="_blank" rel="noreferrer">
        Tatoeba Project
      </a>{" "}
      (CC BY 2.0 FR).
    </footer>
  );
}

function AppRoutes() {
  const { isAuthenticated } = useAuth();
  return (
    <Routes>
      <Route path="/login" element={isAuthenticated ? <Navigate to="/" replace /> : <LoginPage />} />
      <Route
        path="/*"
        element={
          <RequireAuth>
            <Header />
            <Routes>
              <Route path="/" element={<LibraryPage />} />
              <Route path="/import" element={<ImportPage />} />
              <Route path="/texts/:id" element={<ReaderPage />} />
              <Route path="/vocabulary" element={<VocabularyPage />} />
              <Route path="/stats" element={<StatisticsPage />} />
            </Routes>
            <Footer />
          </RequireAuth>
        }
      />
    </Routes>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Router>
          <AppRoutes />
        </Router>
      </AuthProvider>
    </QueryClientProvider>
  );
}
