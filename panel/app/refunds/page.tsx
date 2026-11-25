import type { Metadata } from 'next';
import { LegalPage, Mail, Seller } from '../../components/LegalPage';
import { operator } from '../../lib/operator';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = { title: 'Refunds and right of withdrawal · AIBuilder' };

export default async function Refunds() {
  const op = operator();
  return (
    <LegalPage path="/refunds" title="Refunds and right of withdrawal">
      <div className="card legal-summary">
        <p><b>In short:</b> within 14 days of buying, you can change your mind. For credits, we refund what you have not
          used. For your first Pro subscription, we refund the payment less the days you had the plan. After 14 days you
          can still cancel Pro at any time, and it runs to the end of the month you paid for.</p>
      </div>

      <h2>Your right of withdrawal</h2>
      <p>If you buy as a consumer, you may withdraw from a purchase within 14 days without giving a reason. This applies
        to each purchase of credits and to the first time you subscribe to a paid plan; a monthly renewal is not a new
        purchase. The 14 days start on the day you buy.</p>
      <p>At checkout you ask us to start straight away, so you can use what you bought within those 14 days. If you then
        withdraw:</p>
      <ul>
        <li><b>Credits:</b> we refund the part of that purchase you have not used, that is, the purchase amount or your
          current balance, whichever is smaller.</li>
        <li><b>A plan:</b> we refund the payment less an amount in proportion to the days you had the plan. For example,
          if you withdraw after 3 days of a 30-day month of Pro at $10, we keep $1 and refund $9. Your account then goes
          back to the Free plan.</li>
      </ul>

      <h2>How to withdraw</h2>
      <p>Tell us before the 14 days have passed, for example by email to <Mail op={op} /> or by letter to the address
        below. A clear statement that you withdraw is enough; please include the email address of your account so we can
        find the purchase. You may use the form below, but you do not have to. You meet the deadline if you send your
        message before it ends.</p>

      <h2>How you get your money back</h2>
      <p>We refund you without undue delay, and no later than 14 days after we receive your message, to the payment
        method you used, unless you agree to something else. The refund includes the VAT on the refunded amount, and
        you pay no fee for it. Refunded credits leave your balance.</p>

      <h2>After 14 days</h2>
      <ul>
        <li><b>Plans:</b> cancel at any time on the Billing page. The plan stays until the end of the month you have paid
          for and is not renewed. We do not refund the rest of that month.</li>
        <li><b>Credits:</b> credits do not expire, and unused credits are not refunded, except when we close AIBuilder,
          when we close your account, when you close your account because we changed our terms or made the service
          clearly worse (see the <a href="/terms">terms</a>), or where the law requires it.</li>
      </ul>

      <h2>If something is wrong</h2>
      <p>If AIBuilder does not work as promised, or you think we charged you wrongly, write to <Mail op={op} />. As a
        consumer you have the rights the law gives you when a service is not as agreed, including having it put right,
        a price reduction or a refund, whatever this page says. Please contact us before you dispute a payment with your
        bank: a disputed payment of credits is taken off your balance while the dispute is open.</p>

      <h2>Business customers</h2>
      <p>The right of withdrawal is for consumers. If you buy as a business, the rest of this page and
        the <a href="/terms">terms</a> apply.</p>

      <h2>Standard withdrawal form</h2>
      <p className="muted">Complete and return this form only if you wish to withdraw from the contract.</p>
      <div className="card legal-form">
        <p>To:</p>
        <Seller op={op} />
        <p>I/We (*) hereby give notice that I/We (*) withdraw from my/our (*) contract for the provision of the
          following service (*):</p>
        <p className="legal-blank">&nbsp;</p>
        <p>Ordered on (*):</p>
        <p className="legal-blank">&nbsp;</p>
        <p>Name of consumer(s):</p>
        <p className="legal-blank">&nbsp;</p>
        <p>Address of consumer(s):</p>
        <p className="legal-blank">&nbsp;</p>
        <p>Email address of the account:</p>
        <p className="legal-blank">&nbsp;</p>
        <p>Signature of consumer(s) (only if this form is sent on paper):</p>
        <p className="legal-blank">&nbsp;</p>
        <p>Date:</p>
        <p className="legal-blank">&nbsp;</p>
        <p className="muted">(*) Delete as appropriate.</p>
      </div>
    </LegalPage>
  );
}
