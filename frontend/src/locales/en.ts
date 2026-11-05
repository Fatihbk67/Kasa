import { ACCOUNT } from './en/account';
import { ADMIN } from './en/admin';
import { AUTH } from './en/auth';
import { CHECKOUT } from './en/checkout';
import { COMMON } from './en/common';
import { DEVELOPERS } from './en/developers';
import { MONEY } from './en/money';
import { ONBOARDING } from './en/onboarding';
import { PAYMENTS } from './en/payments';
import { RISK } from './en/risk';
import { SHELL } from './en/shell';
import { SUBSCRIPTIONS } from './en/subscriptions';

/** Each area's words sit together; i18n.test.ts checks no two areas give one Turkish text different English. */
export const EN_PARTS: Record<string, Record<string, string>> = {
  COMMON, SHELL, AUTH, PAYMENTS, CHECKOUT, MONEY, RISK, DEVELOPERS, ACCOUNT, ONBOARDING, SUBSCRIPTIONS, ADMIN,
};

/** English for every Turkish text in the app. */
export const EN: Record<string, string> = Object.assign({}, ...Object.values(EN_PARTS));
