import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import { Layout } from './Layout';
import { Checkout } from './pages/Checkout';
import { Developers } from './pages/Developers';
import { Login, Register } from './pages/Auth';
import { Overview } from './pages/Overview';
import { PaymentDetailPage } from './pages/PaymentDetail';
import { Payments } from './pages/Payments';
import { PayoutDetailPage, Payouts } from './pages/Payouts';
import './styles.css';

function Protected() {
  const { isSignedIn } = useAuth();
  return isSignedIn ? <Layout /> : <Navigate to="/login" replace />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/pay/:id" element={<Checkout />} />
          <Route element={<Protected />}>
            <Route index element={<Overview />} />
            <Route path="payments" element={<Payments />} />
            <Route path="payments/:id" element={<PaymentDetailPage />} />
            <Route path="payouts" element={<Payouts />} />
            <Route path="payouts/:id" element={<PayoutDetailPage />} />
            <Route path="developers" element={<Developers />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
);
