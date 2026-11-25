import type { ReactNode } from 'react';
import { LandingFooter, LandingNav } from './Landing';
import { auth0 } from '../lib/server/auth0';
import { unset, type Operator } from '../lib/operator';

/** The date the legal pages were last changed. Change it with their text, and tell users of material changes. */
export const LEGAL_UPDATED = '25 November 2026';

const PAGES = [
  ['/terms', 'Terms'],
  ['/privacy', 'Privacy'],
  ['/refunds', 'Refunds'],
  ['/contact', 'Contact'],
] as const;

/** A public page of legal text (terms, privacy, refunds, contact) in the landing page's frame. */
export async function LegalPage({ path, title, children }: { path: string; title: string; children: ReactNode }) {
  const signedIn = !!(await auth0.getSession());
  return (
    <div className="landing">
      <LandingNav signedIn={signedIn} />
      <article className="legal">
        <nav className="legal-nav" aria-label="Legal pages">
          {PAGES.map(([href, name]) => href === path
            ? <b key={href} aria-current="page">{name}</b>
            : <a key={href} href={href}>{name}</a>)}
        </nav>
        <h1>{title}</h1>
        <p className="muted">Last updated {LEGAL_UPDATED}</p>
        {children}
      </article>
      <LandingFooter />
    </div>
  );
}

/** The contact address as a mailto link, or the placeholder as text while CONTACT_EMAIL is unset. */
export function Mail({ op }: { op: Operator }) {
  return unset(op.email) ? <span>{op.email}</span> : <a href={`mailto:${op.email}`}>{op.email}</a>;
}

/** The seller's name, address, CVR and email, as one block. */
export function Seller({ op }: { op: Operator }) {
  return (
    <address className="legal-seller">
      <b>{op.name}</b><br />
      {op.address}<br />
      CVR {op.cvr}<br />
      <Mail op={op} />
    </address>
  );
}
