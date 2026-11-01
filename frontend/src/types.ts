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
  description: string | null;
  failureCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Operation {
  id: string;
  type: 'AUTHORIZE' | 'CAPTURE' | 'VOID' | 'REFUND';
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
  type: 'CAPTURE' | 'REFUND';
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
  status: 'OPEN' | 'PROCESSING' | 'COMPLETED' | 'EXPIRED';
  failureCode: string | null;
  paymentStatus: PaymentStatus | null;
  expiresAt: string;
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

export interface Tenant {
  id: string;
  name: string;
}
