import type { Metadata } from 'next';
import { LegalPage, Mail, Seller } from '../../components/LegalPage';
import { operator } from '../../lib/operator';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = { title: 'Privacy policy · AIBuilder' };

export default async function Privacy() {
  const op = operator();
  return (
    <LegalPage path="/privacy" title="Privacy policy">
      <p>This policy explains what personal data AIBuilder collects, why, who receives it, how long we keep it, and
        your rights. It follows the EU General Data Protection Regulation (GDPR) and the Danish Data Protection Act.</p>

      <h2>1. Who is responsible</h2>
      <p>The data controller is:</p>
      <Seller op={op} />
      <p>Write to <Mail op={op} /> about anything in this policy.</p>

      <h2>2. What we collect and why</h2>
      <div className="legal-table">
        <table>
          <thead>
            <tr><th>Data</th><th>Why</th><th>Legal basis</th><th>How long</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>Your account: email address, name and profile picture from your sign-in, and an account ID</td>
              <td>To sign you in and run your account, and to email you about it</td>
              <td>Contract (Art. 6(1)(b))</td>
              <td>Until your account is deleted</td>
            </tr>
            <tr>
              <td>Sign-in records at Auth0: IP address, browser and time</td>
              <td>To keep accounts secure</td>
              <td>Legitimate interest in security (Art. 6(1)(f))</td>
              <td>Up to 30 days</td>
            </tr>
            <tr>
              <td>Your servers and teams: server names, the Minecraft and plugin versions a server reports, when it was
                last online, members and their roles, invites and access tokens</td>
              <td>To connect your servers and let the people you invite work on them</td>
              <td>Contract</td>
              <td>Until you delete the server, leave it or revoke the token; invites expire after 7 days</td>
            </tr>
            <tr>
              <td>What is built: the history of edits (which blocks changed, pictures of them, and the name of the
                player or AI client that made each edit) and the assets you save or upload</td>
              <td>To show history, undo edits and keep your asset library</td>
              <td>Contract</td>
              <td>History: the newest 5,000 entries or 2 GB per server, until the server is deleted. Assets: until you
                delete them</td>
            </tr>
            <tr>
              <td>AI chats: your messages, the AI&apos;s answers, its tool calls and their results (including pictures
                of your world and the names and positions of players online), and the tokens and cost of each chat</td>
              <td>To run the chat and let you continue it later</td>
              <td>Contract</td>
              <td>Until you delete the chat or its server</td>
            </tr>
            <tr>
              <td>Your API keys for the chat (Anthropic, OpenRouter), encrypted</td>
              <td>To send your chat requests on your own key</td>
              <td>Contract</td>
              <td>Until you remove the key</td>
            </tr>
            <tr>
              <td>Linked Minecraft accounts: player ID and player name</td>
              <td>To save assets from inside the game into your library</td>
              <td>Contract</td>
              <td>Until you unlink the player</td>
            </tr>
            <tr>
              <td>Payments: your Stripe customer ID, plan, subscription status and dates, and your credit history (top-ups,
                what each chat turn cost, refunds)</td>
              <td>To sell and run plans and credits, and to keep accounts as the law requires</td>
              <td>Contract, and legal obligation (Art. 6(1)(c)) under the Danish Bookkeeping Act</td>
              <td>Invoices and payment records (at Stripe and in our bookkeeping): 5 years from the end of the financial
                year they belong to, also after your account is deleted. The rest, including your credit history: until
                your account is deleted</td>
            </tr>
            <tr>
              <td>Technical logs: IP addresses in request logs, and the address of a plugin that tries to connect with a
                wrong token</td>
              <td>To keep the service secure and find faults</td>
              <td>Legitimate interest in security</td>
              <td>Up to 30 days</td>
            </tr>
            <tr>
              <td>Emails you send us</td>
              <td>To answer you</td>
              <td>Contract, or our legitimate interest in answering</td>
              <td>Until the matter is closed and up to 12 months after, unless we need them longer for a legal claim</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>We need your email address to create an account, and payment details to sell you a plan or credits. Everything
        else depends on the features you use. We do not use your data for advertising, do not sell it, and make no
        automated decisions about you that have legal or similarly significant effects.</p>

      <h2>3. Players on your server</h2>
      <p>When a server is connected, the plugin sends us the names, positions and view direction of players online, so
        that the AI and the map can see them, and the name of whoever makes an edit, for the history. For this data the
        owner of the server is the controller and we process it on the owner&apos;s behalf (see section 10 of
        the <a href="/terms">terms</a>). If you play on a server that uses AIBuilder and have questions, ask its
        owner; we will help them answer.</p>

      <h2>4. Who receives data</h2>
      <p>We use these service providers, who process data for us under data processing agreements:</p>
      <div className="legal-table">
        <table>
          <thead>
            <tr><th>Provider</th><th>What for</th></tr>
          </thead>
          <tbody>
            <tr><td>{op.hosting}</td><td>Our servers and database</td></tr>
            <tr><td>Okta, Inc. (Auth0), USA; our Auth0 account is in its EU region</td><td>Sign-in</td></tr>
            <tr><td>Cloudflare, Inc. (R2), USA</td><td>Storage of history pictures and diffs, assets and Minecraft files</td></tr>
            <tr><td>Stripe Payments Europe, Ltd., Ireland, and Stripe, Inc., USA</td><td>Payments, receipts and tax</td></tr>
            <tr><td>Anthropic, PBC, USA</td><td>The AI model behind chats on credits: it receives the chat&apos;s messages,
              tool results and pictures</td></tr>
          </tbody>
        </table>
      </div>
      <p>Stripe also uses payment data as a controller of its own, for example to prevent fraud and meet its legal
        duties; see Stripe&apos;s privacy policy.</p>
      <p>When you chat on your own API key, we send the chat to the provider you chose (Anthropic or OpenRouter) under
        your own account with it; its privacy policy applies to what it does with the data. OpenRouter may pass the
        request on to the company that runs the model you picked.</p>
      <p>Other people see some of your data because you work together: members of a server see each other&apos;s name
        or email and picture, the history (including who made each edit) and drafts. We also give data to authorities
        when the law requires it.</p>

      <h2>5. Transfers outside the EU</h2>
      <p>Some providers above are in the USA or may process data there. Those transfers are covered by the EU-US Data
        Privacy Framework or by the European Commission&apos;s standard contractual clauses, as applicable. Write to us
        for a copy of the safeguards.</p>

      <h2>6. Cookies and storage in your browser</h2>
      <p>We set only the cookies AIBuilder needs to work: a session cookie that keeps you signed in, and a short-lived
        cookie during sign-in. They need no consent. The dashboard also remembers in your browser&apos;s local storage
        which tips you have seen. We use no analytics or advertising cookies. If we add any, we will ask for your
        consent first.</p>

      <h2>7. Security</h2>
      <p>Connections to AIBuilder are encrypted. API keys are stored encrypted, tokens are never stored in plain text,
        and the plugin connects out to us so your server opens no port. Access to data is limited to those who
        need it to run the service.</p>

      <h2>8. Deleting your data</h2>
      <p>You can delete chats, assets, API keys, tokens and whole servers yourself; deleting a server deletes its
        history, chats and stored files. To delete your account, write to <Mail op={op} /> from the email address on
        the account. We delete it within 30 days, except accounting records we must keep. Copies in backups are
        overwritten within 30 days after that.</p>

      <h2>9. Your rights</h2>
      <p>You have the right to:</p>
      <ul>
        <li>see the data we hold about you and get a copy of it;</li>
        <li>have wrong data corrected;</li>
        <li>have your data deleted;</li>
        <li>have its use restricted;</li>
        <li>get the data you gave us in a machine-readable format;</li>
        <li>object to our use of data based on legitimate interest;</li>
        <li>withdraw any consent you gave, without affecting what was done before.</li>
      </ul>
      <p>Write to <Mail op={op} />. We answer within one month and may ask you to confirm who you are. You can also
        complain to the Danish Data Protection Agency, <a href="https://www.datatilsynet.dk">Datatilsynet</a>, Carl
        Jacobsens Vej 35, 2500 Valby, Denmark.</p>

      <h2>10. Age</h2>
      <p>AIBuilder is for people aged 18 and over. If we learn that we hold data about someone younger, we delete their
        account.</p>

      <h2>11. Changes</h2>
      <p>When we change this policy in a way that matters to you, we email you before the change takes effect. The
        date at the top shows when it last changed.</p>
    </LegalPage>
  );
}
