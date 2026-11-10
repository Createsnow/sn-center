import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './lib/auth';
import { ChangePassword } from './pages/ChangePassword';
import { Login } from './pages/Login';
import { AppLayout } from './shell/AppLayout';
import './sn.css';

/** sn 功能入口：未登录跳登录页（记下原地址），其余进入主框架。 */
export default function SnApp() {
  const token = useAuth((s) => s.token);
  const location = useLocation();
  return (
    <div className="sn-root">
      <Routes>
        <Route path="login" element={<Login />} />
        <Route
          path="*"
          element={
            token ? (
              <AuthedRoutes />
            ) : (
              <Navigate
                to={`/sn/login?redirect=${encodeURIComponent(location.pathname + location.search)}`}
                replace
              />
            )
          }
        />
      </Routes>
    </div>
  );
}

function AuthedRoutes() {
  return (
    <Routes>
      <Route path="password" element={<ChangePassword />} />
      <Route path="export" element={<Navigate to="/sn/allocate" replace />} />
      <Route path="*" element={<AppLayout />} />
    </Routes>
  );
}
