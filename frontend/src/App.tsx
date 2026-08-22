import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link, Navigate, Route, BrowserRouter as Router, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { LoginPage } from "./auth/LoginPage";
import { ImportPage } from "./texts/ImportPage";
import { LibraryPage } from "./texts/LibraryPage";
import { ReaderPage } from "./texts/ReaderPage";

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
      <button type="button" className="link-button" onClick={logout}>
        Log out
      </button>
    </header>
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
            </Routes>
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
