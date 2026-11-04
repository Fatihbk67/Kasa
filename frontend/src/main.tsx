import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import { Layout } from './Layout';
import { AdminHome, AdminRunDetail } from './pages/Admin';
import { Analytics } from './pages/Analytics';
import { AuditPage } from './pages/Audit';
import { BankChallenge } from './pages/BankChallenge';
import { Checkout } from './pages/Checkout';
import { CustomerDetailPage, Customers } from './pages/Customers';
import { DisputeDetailPage, Disputes } from './pages/Disputes';
import { PaymentLinks, PublicLinkPage } from './pages/PaymentLinks';
import { SubscriptionDetailPage, Subscriptions } from './pages/Subscriptions';
import { Settings } from './pages/Settings';
import { AcceptInvitation, TeamPage } from './pages/Team';
import { Developers } from './pages/Developers';
import { ForgotPassword, Login, Register, ResetPassword } from './pages/Auth';
import { Onboarding } from './pages/Onboarding';
import { RiskPage } from './pages/Risk';
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
          <Route path="/forgot-password" element={<ForgotPassword />} />
          <Route path="/reset-password/:token" element={<ResetPassword />} />
          <Route path="/pay/:id" element={<Checkout />} />
          <Route path="/bank/3ds/:id" element={<BankChallenge />} />
          <Route path="/l/:slug" element={<PublicLinkPage />} />
          <Route path="/invite/:token" element={<AcceptInvitation />} />
          <Route path="/admin" element={<AdminHome />} />
          <Route path="/admin/reconciliation/:id" element={<AdminRunDetail />} />
          <Route element={<Protected />}>
            <Route index element={<Overview />} />
            <Route path="analytics" element={<Analytics />} />
            <Route path="payments" element={<Payments />} />
            <Route path="payments/:id" element={<PaymentDetailPage />} />
            <Route path="subscriptions" element={<Subscriptions />} />
            <Route path="subscriptions/:id" element={<SubscriptionDetailPage />} />
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
            <Route path="onboarding" element={<Onboarding />} />
            <Route path="risk" element={<RiskPage />} />
            <Route path="settings" element={<Settings />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
);
