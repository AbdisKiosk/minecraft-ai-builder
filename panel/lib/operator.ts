// Who sells AIBuilder, for the legal pages (terms, privacy, refunds, contact). The values come from the environment,
// so no names or addresses are in the repository, and are read on every request. An unset value shows as a bracketed
// placeholder such as "[COMPANY_NAME]", so a page that is missing one is obvious.

export interface Operator {
  /** The company's legal name. */
  name: string;
  /** Its registered address, on one line. */
  address: string;
  /** Its Danish CVR number. */
  cvr: string;
  /** Where users write for support, withdrawal, privacy requests and complaints. */
  email: string;
  /** Who hosts the panel and its database, and where, e.g. "Hetzner Online GmbH, Germany". */
  hosting: string;
}

const KEYS = { name: 'COMPANY_NAME', address: 'COMPANY_ADDRESS', cvr: 'COMPANY_CVR', email: 'CONTACT_EMAIL', hosting: 'HOSTING_PROVIDER' } as const;

/** True when the value is a placeholder for an unset variable. */
export function unset(value: string): boolean {
  return value.startsWith('[') && value.endsWith(']');
}

export function operator(env: Record<string, string | undefined> = process.env): Operator {
  const read = (key: string) => env[key]?.trim() || `[${key}]`;
  return {
    name: read(KEYS.name),
    address: read(KEYS.address),
    cvr: read(KEYS.cvr),
    email: read(KEYS.email),
    hosting: read(KEYS.hosting),
  };
}

/** The Danish VAT number, which is "DK" and the CVR number. */
export function vatNumber(op: Operator): string {
  return unset(op.cvr) ? op.cvr : `DK${op.cvr.replace(/\s+/g, '')}`;
}
