import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { RequireAuth, RequirePairing } from "./components/ProtectedRoute";
import { AuthProvider } from "./context/AuthContext";
import { ClientSocketProvider } from "./context/ClientSocketContext";
import { ConnectPage } from "./pages/ConnectPage";
import { LoginPage } from "./pages/LoginPage";
import { MonitoringPage } from "./pages/MonitoringPage";

export default function App() {
  return (
    <AuthProvider>
      <ClientSocketProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<LoginPage />} />
            <Route
              path="/connect"
              element={
                <RequireAuth>
                  <ConnectPage />
                </RequireAuth>
              }
            />
            <Route
              path="/monitor"
              element={
                <RequireAuth>
                  <RequirePairing>
                    <MonitoringPage />
                  </RequirePairing>
                </RequireAuth>
              }
            />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </ClientSocketProvider>
    </AuthProvider>
  );
}
