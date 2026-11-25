import type { Metadata } from 'next';
import { LegalPage, Mail } from '../../components/LegalPage';
import { operator, vatNumber } from '../../lib/operator';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = { title: 'Contact · AIBuilder' };

export default async function Contact() {
  const op = operator();
  return (
    <LegalPage path="/contact" title="Contact">
      <p>For help, questions about your account or billing, withdrawing a purchase, privacy requests and complaints,
        write to <Mail op={op} />. Please send it from the email address on your account, so we can find it.</p>

      <h2>Company</h2>
      <dl className="legal-facts">
        <dt>Name</dt><dd>{op.name}</dd>
        <dt>Address</dt><dd>{op.address}</dd>
        <dt>CVR</dt><dd>{op.cvr}</dd>
        <dt>VAT number</dt><dd>{vatNumber(op)}</dd>
        <dt>Email</dt><dd><Mail op={op} /></dd>
      </dl>

      <h2>Complaints</h2>
      <p>If you are unhappy with AIBuilder or a purchase, write to us first and we will try to put it right. If we cannot
        agree, you can complain to <a href="https://www.naevneneshus.dk">Nævnenes Hus</a>, Toldboden 2, 8800 Viborg,
        Denmark. If you live in another EU country, the European Consumer Centre Denmark
        (<a href="https://www.forbrugereuropa.dk">Forbrugereuropa</a>) can help with a complaint against a Danish
        company.</p>
      <p>Complaints about how we handle personal data can also go to the Danish Data Protection
        Agency, <a href="https://www.datatilsynet.dk">Datatilsynet</a>.</p>

      <p className="muted">AIBuilder is not affiliated with Mojang or Microsoft.</p>
    </LegalPage>
  );
}
