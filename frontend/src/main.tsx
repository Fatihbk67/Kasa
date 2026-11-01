import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import { Layout } from './Layout';
import { AdminHome, AdminRunDetail } from './pages/Admin';
import { Analytics } from './pages/Analytics';
import { AuditPage } from './pages/Audit';
import { Checkout } from './pages/Checkout';
import { CustomerDetailPage, Customers } from './pages/Customers';
import { DisputeDetailPage, Disputes } from './pages/Disputes';
import { PaymentLinks, PublicLinkPage } from './pages/PaymentLinks';
import { Settings } from './pages/Settings';
import { AcceptInvitation, TeamPage } from './pages/Team';
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
          <Route path="/l/:slug" element={<PublicLinkPage />} />
          <Route path="/invite/:token" element={<AcceptInvitation />} />
          <Route path="/admin" element={<AdminHome />} />
          <Route path="/admin/reconciliation/:id" element={<AdminRunDetail />} />
          <Route element={<Protected />}>
            <Route index element={<Overview />} />
            <Route path="analytics" element={<Analytics />} />
            <Route path="payments" element={<Payments />} />
            <Route path="payments/:id" element={<PaymentDetailPage />} />
            <Route path="disputes" element={<Disputes />} />
            <Route path="disputes/:id" element={<DisputeDetailPage />} />
            <Route path="customers" element={<Customers />} />
            <Route path="customers/:id" element={<CustomerDetailPage />} />
            <Route path="payment-links" element={<PaymentLinks />} />
            <Route path="payouts" element={<Payouts />} />
            <Route path="payouts/:id" element={<PayoutDetailPage />} />
            <Route path="developers" element={<Developers />} />
            <Route path="team" element={<TeamPage />} />
            <Route path="audit" element={<AuditPage />} />
            <Route path="settings" element={<Settings />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
);
