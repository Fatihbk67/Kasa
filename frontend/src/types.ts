export type PaymentStatus =
  | 'PENDING'
  | 'AUTHORIZED'
  | 'DECLINED'
  | 'FAILED'
  | 'VOIDED'
  | 'CAPTURED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED';

export type OperationStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED';

export interface Payment {
  id: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  capturedAmount: number;
  refundedAmount: number;
  feeAmount: number;
  disputedAmount: number;
  customerId: string | null;
  description: string | null;
  failureCode: string | null;
  createdAt: string;
  updatedAt: string;
  /** Null for payments made before screening existed. */
  riskLevel: 'NORMAL' | 'REVIEW' | 'BLOCKED' | null;
  riskReasons: RiskReason[];
  reviewStatus: 'OPEN' | 'APPROVED' | 'REJECTED' | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  threeDsMethod: 'FRICTIONLESS' | 'CHALLENGE' | null;
  threeDsEci: string | null;
  installments: number;
}

export type RiskRuleKind = 'CARD_VELOCITY' | 'CARD_DECLINES' | 'AMOUNT_ABOVE';
export type RiskReason = RiskRuleKind | 'CARD_BLOCKLISTED';

export interface RiskRule {
  id: string;
  kind: RiskRuleKind;
  threshold: number;
  windowMinutes: number | null;
  currency: string | null;
  action: 'REVIEW' | 'BLOCK';
  enabled: boolean;
  createdAt: string;
}

export interface BlockedCard {
  fingerprint: string;
  reason: string;
  sourcePayment: string | null;
  createdAt: string;
}

export interface Operation {
  id: string;
  type: 'AUTHORIZE' | 'CAPTURE' | 'VOID' | 'REFUND' | 'DISPUTE_WITHDRAWAL' | 'DISPUTE_REVERSAL' | 'DISPUTE_FEE';
  amount: number;
  status: OperationStatus;
  failureCode: string | null;
  createdAt: string;
}

export interface OperationResult {
  payment: Payment;
  operation: Operation;
}

export interface PaymentDetail {
  payment: Payment;
  operations: Operation[];
}

export interface PaymentPage {
  items: Payment[];
  nextCursor: string | null;
}

export interface Balance {
  currency: string;
  available: number;
  inTransit: number;
}

export interface Payout {
  id: string;
  currency: string;
  amount: number;
  status: 'PENDING' | 'PAID' | 'FAILED';
  settlementDate: string;
  failureCode: string | null;
  paidAt: string | null;
  createdAt: string;
}

export interface PayoutItem {
  paymentId: string;
  operationId: string;
  type: 'CAPTURE' | 'REFUND' | 'DISPUTE_WITHDRAWAL' | 'DISPUTE_REVERSAL' | 'DISPUTE_FEE';
  grossAmount: number;
  feeAmount: number;
  netAmount: number;
  released: boolean;
}

export interface PayoutDetail {
  payout: Payout;
  items: PayoutItem[];
}

export interface ApiKey {
  id: string;
  name: string;
  prefix: string;
  createdAt: string;
  revokedAt: string | null;
}

export interface WebhookEndpoint {
  id: string;
  url: string;
  createdAt: string;
  disabledAt: string | null;
}

export interface WebhookDelivery {
  id: string;
  eventId: string;
  eventType: string;
  status: 'PENDING' | 'DELIVERED' | 'FAILED' | 'CANCELLED';
  attempts: number;
  lastStatusCode: number | null;
  lastError: string | null;
  createdAt: string;
}

export interface CheckoutSession {
  id: string;
  merchantName: string;
  amount: number;
  currency: string;
  description: string | null;
  status: 'OPEN' | 'PROCESSING' | 'AUTHENTICATING' | 'COMPLETED' | 'EXPIRED';
  failureCode: string | null;
  paymentStatus: PaymentStatus | null;
  expiresAt: string;
  /** Set while the customer must verify themselves on their bank's page. */
  challengeUrl: string | null;
  installments: number;
}

export interface BankChallenge {
  id: string;
  merchantName: string;
  amount: number;
  currency: string;
  cardHint: string;
  status: 'PENDING' | 'Y' | 'N' | 'EXPIRED';
  attemptsLeft: number;
  expiresAt: string;
  testCode: string;
  returnUrl: string;
}

export interface SignedBankResult {
  authenticationId: string;
  status: 'Y' | 'N';
  eci: string | null;
  issuedAt: number;
  signature: string;
}

export interface BankAnswer {
  status: 'PENDING' | 'Y' | 'N';
  attemptsLeft: number;
  result: SignedBankResult | null;
  returnUrl: string | null;
}

export interface DayPoint {
  day: string;
  captured: number;
  refunded: number;
  captureCount: number;
}

export interface AnalyticsSummary {
  days: number;
  currency: string;
  totals: {
    captured: number;
    refunded: number;
    fees: number;
    disputed: number;
    disputeFees: number;
    net: number;
    captureCount: number;
    authorizationAttempts: number;
    authorizationsApproved: number;
  };
  daily: DayPoint[];
  statuses: { status: PaymentStatus; count: number; amount: number }[];
  failures: { code: string; count: number }[];
}

export interface PlatformOverview {
  tenantCount: number;
  paymentCount: number;
  capturesLastDay: number;
  pendingOperations: number;
  pendingPayouts: number;
  failedPayouts: number;
  queuedWebhooks: number;
  failedWebhooks: number;
}

export interface ReconciliationRun {
  id: string;
  statementDate: string;
  status: 'COMPLETED' | 'FAILED';
  bankLineCount: number;
  matchedCount: number;
  discrepancyCount: number;
  error: string | null;
  createdAt: string;
}

export interface Discrepancy {
  kind: string;
  bankReference: string | null;
  tenantId: string | null;
  sourceId: string | null;
  expectedAmount: number | null;
  actualAmount: number | null;
  detail: string | null;
}

export interface ReconciliationDetail {
  run: ReconciliationRun;
  discrepancies: Discrepancy[];
}

export type Role = 'OWNER' | 'ADMIN' | 'VIEWER';

export interface Me {
  userId: string;
  email: string;
  role: Role;
  tenantId: string;
  tenantName: string;
  tenantStatus: 'PENDING' | 'ACTIVE' | 'SUSPENDED';
  tenantStatusReason: string | null;
  emailVerified: boolean;
}

export interface TeamMember {
  id: string;
  email: string;
  role: Role;
  disabledAt: string | null;
  createdAt: string;
}

export interface TeamInvitation {
  id: string;
  email: string;
  role: Role;
  expiresAt: string;
  createdAt: string;
}

export interface Team {
  members: TeamMember[];
  invitations: TeamInvitation[];
}

export interface PublicInvitation {
  tenantName: string;
  email: string;
  role: Role;
  status: 'OPEN' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';
}

export interface AuditEntry {
  seq: number;
  createdAt: string;
  actorType: 'USER' | 'API_KEY' | 'SYSTEM';
  actorLabel: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  metadata: string;
  ip: string | null;
}

export interface AuditPage {
  items: AuditEntry[];
  nextCursor: number | null;
}

export interface ChainReport {
  valid: boolean;
  checked: number;
  brokenAtSeq: number | null;
}

export interface Plan {
  id: string;
  name: string;
  amount: number;
  currency: string;
  billingInterval: 'MONTH' | 'YEAR';
  archived: boolean;
  activeSubscriptions: number;
  createdAt: string;
}

export type SubscriptionStatus = 'INCOMPLETE' | 'ACTIVE' | 'PAST_DUE' | 'CANCELED';
export type InvoiceStatus = 'OPEN' | 'PAID' | 'VOID' | 'UNCOLLECTIBLE';

export interface Subscription {
  id: string;
  customerId: string;
  customerLabel: string | null;
  planId: string;
  planName: string;
  amount: number;
  currency: string;
  billingInterval: Plan['billingInterval'];
  status: SubscriptionStatus;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  cancelAtPeriodEnd: boolean;
  creditBalance: number;
  cancelReason: string | null;
  canceledAt: string | null;
  createdAt: string;
}

export interface Invoice {
  id: string;
  kind: 'FIRST' | 'RENEWAL' | 'PLAN_CHANGE';
  status: InvoiceStatus;
  periodStart: string;
  periodEnd: string;
  subtotal: number;
  creditApplied: number;
  amount: number;
  currency: string;
  attemptCount: number;
  nextAttemptAt: string | null;
  paymentId: string | null;
  failureCode: string | null;
  paidAt: string | null;
  createdAt: string;
  lines: { kind: string; planName: string; amount: number }[];
}

export interface SubscriptionDetail {
  subscription: Subscription;
  invoices: Invoice[];
}

export interface SubscriptionPage {
  items: Subscription[];
  nextCursor: string | null;
}

export interface BillingSummary {
  revenue: { currency: string; monthlyRecurring: number }[];
  active: number;
  pastDue: number;
  canceledLast30Days: number;
}

export type DisputeStatus = 'NEEDS_RESPONSE' | 'UNDER_REVIEW' | 'WON' | 'LOST';
export type DisputeReason = 'FRAUDULENT' | 'PRODUCT_NOT_RECEIVED' | 'DUPLICATE' | 'PRODUCT_UNACCEPTABLE' | 'OTHER';

export interface Dispute {
  id: string;
  paymentId: string;
  amount: number;
  currency: string;
  reason: DisputeReason;
  status: DisputeStatus;
  feeAmount: number;
  respondBy: string;
  evidenceExplanation: string | null;
  evidenceTracking: string | null;
  evidenceCommunication: string | null;
  evidenceSubmittedAt: string | null;
  resolvedAt: string | null;
  createdAt: string;
}

export interface DisputePage {
  items: Dispute[];
  nextCursor: string | null;
}

export interface Customer {
  id: string;
  email: string | null;
  name: string | null;
  anonymized: boolean;
  createdAt: string;
  totals: { currency: string; spent: number; paymentCount: number }[];
}

export interface CustomerPage {
  items: Customer[];
  nextCursor: string | null;
}

export type PaymentLinkStatus = 'OPEN' | 'SOLD_OUT' | 'EXPIRED' | 'DISABLED';

export interface PaymentLink {
  id: string;
  slug: string;
  title: string;
  description: string | null;
  amount: number | null;
  currency: string;
  maxPayments: number | null;
  expiresAt: string | null;
  status: PaymentLinkStatus;
  paidCount: number;
  collected: number;
  createdAt: string;
}

export interface PublicPaymentLink {
  merchantName: string;
  title: string;
  description: string | null;
  amount: number | null;
  currency: string;
  status: PaymentLinkStatus;
}

export type BusinessType = 'INDIVIDUAL' | 'SOLE_PROPRIETOR' | 'COMPANY';
export type Sector = 'ECOMMERCE' | 'FOOD' | 'EDUCATION' | 'SERVICES' | 'RETAIL' | 'SOFTWARE' | 'OTHER';

export interface BusinessProfile {
  businessType: BusinessType;
  legalName: string;
  taxId: string;
  taxOffice: string;
  sector: Sector;
  website: string | null;
  phone: string;
  address: string;
  city: string;
  /** Masked for the merchant, in full only in the platform's review. */
  iban: string;
  bankCode: string;
  submittedAt: string;
  editable: boolean;
}

export interface AppNotification {
  id: string;
  kind: string;
  title: string;
  body: string;
  link: string | null;
  read: boolean;
  createdAt: string;
}

export interface Inbox {
  items: AppNotification[];
  unread: number;
}
