import type { Metadata } from 'next';
import { LegalPage, Mail, Seller } from '../../components/LegalPage';
import { MARKUP, MAX_TOPUP, MIN_TOPUP } from '../../lib/credits';
import { operator } from '../../lib/operator';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = { title: 'Terms of service · AIBuilder' };

export default async function Terms() {
  const op = operator();
  return (
    <LegalPage path="/terms" title="Terms of service">
      <p>These terms are the agreement between you and us when you use AIBuilder: the website, the dashboard, the AI
        chat and the AIBuilder plugin for Minecraft servers. By creating an account or buying a plan or credits you
        accept them. Our <a href="/privacy">privacy policy</a> says how we handle personal data, and
        the <a href="/refunds">refunds page</a> explains your right of withdrawal.</p>

      <h2>1. Who we are</h2>
      <p>AIBuilder is run by:</p>
      <Seller op={op} />
      <p>A company registered in Denmark. &quot;We&quot; and &quot;us&quot; mean that company; &quot;you&quot; means the
        person or business using AIBuilder.</p>

      <h2>2. What AIBuilder is</h2>
      <p>AIBuilder lets an AI build on your Minecraft server. You add a server on the website and install the plugin we
        give you; the plugin connects out to us, and an AI assistant that you connect (or our AI chat in the browser)
        places blocks through it. We show live jobs, keep a history of edits with pictures and revert, offer review mode
        with drafts, an asset library, a world map, and let you invite others to build with you. What each plan
        includes is on the <a href="/pricing">pricing page</a>.</p>
      <p>To use it you need a Minecraft: Java Edition server that you run or may install plugins on, running Paper or
        Spigot (or a compatible server) with WorldEdit 6.1 or later, WorldEdit 7.2 or later, or FastAsyncWorldEdit,
        on Minecraft 1.8 up to the newest release, and Java 8 or later. You also need a current web browser (the
        interactive 3D view needs WebGL 2), and either an AI assistant that can use MCP servers, an Anthropic or
        OpenRouter API key, or credits. The plugin contains a token that lets only your server connect as itself.</p>

      <h2>3. Your account</h2>
      <ul>
        <li>You must be at least 18 years old to create an account and to buy a plan or credits.</li>
        <li>Give us a working email address and keep your sign-in details to yourself.</li>
        <li>Treat access tokens and the plugin jar like passwords: anyone who has them can build on your server. If one
          leaks, make a new token or give the server a new key on its page.</li>
        <li>You are responsible for what is done under your account and with your tokens.</li>
        <li>Only add servers you have the right to install plugins on, and only invite people you want to let edit
          them. Members of a server use the plan of its owner.</li>
      </ul>

      <h2>4. Plans and payment</h2>
      <ul>
        <li>Prices are in US dollars and include VAT, or sales tax where it applies. The tax is worked out from your
          country at checkout. If you buy as a business with a valid VAT number in another EU country, no VAT is
          charged and you account for it under the reverse-charge rules.</li>
        <li>Payments are handled by Stripe. We never see your full card number. Stripe emails you a receipt.</li>
        <li>A paid plan is a subscription that renews every month at the current price until you cancel it. You can
          cancel at any time on the Billing page; the plan then stays until the end of the month you have paid for, and
          you are not charged again. Apart from your right of withdrawal (see the <a href="/refunds">refunds page</a>)
          and your rights under the law, we do not refund the rest of a month you cancel.</li>
        <li>If a payment fails, Stripe tries again for a while and you keep your plan meanwhile. If it keeps failing,
          your account goes back to the Free plan.</li>
        <li>When you move to a smaller plan, or back to Free, nothing is deleted: what you have beyond the new plan&apos;s
          limits stays, but you cannot add more until you are within them.</li>
        <li>The plan of a server is the plan of the account that owns it, and only the owner pays for it.</li>
        <li>For a limited time, Pro includes everything in Studio. When that ends, Pro keeps the features listed for it
          on the pricing page. We will email Pro subscribers at least 30 days before, and you can cancel before then.</li>
        <li>If we raise the price of your subscription, we email you at least 30 days before it applies to you, and you
          can cancel before then.</li>
      </ul>

      <h2>5. Credits</h2>
      <ul>
        <li>Credits are a prepaid balance in US dollars that pays for the AI chat on our own AI account, so you need no
          API key of your own. You buy them in amounts from ${MIN_TOPUP} to ${MAX_TOPUP}. VAT is included in what you
          pay: ${MIN_TOPUP} paid gives ${MIN_TOPUP} of credits.</li>
        <li>Each request the chat makes to the AI model costs what the model&apos;s provider (Anthropic) charges for it,
          plus {Math.round(MARKUP * 100)}%. The price per million tokens of each model is shown in the model picker before
          you send a message, and each chat shows what it has cost. A request you stop is charged for what the model
          produced before it stopped.</li>
        <li>A message needs a balance above zero. Because a request is charged when it finishes, the balance can go
          below zero by at most one request; your next top-up covers that.</li>
        <li>If the provider changes its prices, the new price applies to requests after the change. Your balance in
          dollars does not change.</li>
        <li>Credits do not expire. They are not money, earn no interest, cannot be exchanged for cash and cannot be
          moved to another account.</li>
        <li>Unused credits are refunded only as the <a href="/refunds">refunds page</a> describes: within 14 days of
          buying them, when we close the service or your account, or where the law requires it.</li>
      </ul>

      <h2>6. Using your own API key</h2>
      <p>Instead of credits, you can let the chat use your own Anthropic or OpenRouter API key. The provider then bills
        you directly and its terms and privacy policy apply between you and it; we charge nothing for those requests. We
        store your key encrypted, use it only for your own chats, and show only its last four characters again. Set a
        spending limit with the provider: we cannot see or limit what it charges you.</p>

      <h2>7. The AI and your world</h2>
      <p>AI models make mistakes. The AI places, changes and removes blocks in your world when you or a member of your
        server asks it to, and its results may not be what you wanted. Look at what it builds before you rely on it.
        Review mode, history and revert help you undo edits, but they do not record everything (for example sign text
        and chest contents) and are not a backup. Keep your own backups of your worlds.</p>

      <h2>8. Acceptable use</h2>
      <p>You may not use AIBuilder:</p>
      <ul>
        <li>to break the law or anyone&apos;s rights;</li>
        <li>in a way that breaks <a href="https://www.anthropic.com/legal/aup">Anthropic&apos;s Usage Policy</a> when
          you chat on credits, or the terms of your own provider when you chat on your own key;</li>
        <li>in a way that breaks the Minecraft End User License Agreement or Mojang&apos;s usage guidelines;</li>
        <li>to get into servers, accounts or data you have not been given access to, or to attack, overload or probe
          the service;</li>
        <li>to get around plan limits or credit charges, or to resell or share access to AIBuilder or to our AI account;</li>
        <li>to upload or share content you do not have the right to use.</li>
      </ul>

      <h2>9. Your content</h2>
      <p>What you build, the assets and schematics you save or upload, and your chats are yours. You give us the rights
        we need to store, copy, process and display them, and to send them to the service providers listed in
        the <a href="/privacy">privacy policy</a>, only to run AIBuilder for you and the people you share them with.
        Those rights end when you delete the content, except for copies the law requires us to keep. When you share an
        asset, the people you share it with can place it on their servers. If you send us ideas or feedback, we may use
        them without owing you anything.</p>

      <h2>10. Data we process for you</h2>
      <p>When your server is connected, the plugin sends us the names, positions and view direction of players online
        on it, the name of whoever made each edit, and the player name and ID of players who link their account. For
        those players we process the data on your behalf, as your data processor under Article 28 of the GDPR, and
        you decide what happens to it. These are the terms of that processing:</p>
      <ul>
        <li><b>What and why:</b> player names, Minecraft player IDs, positions and view direction, and edit history,
          processed only to run AIBuilder for your server for as long as the server is on your account.</li>
        <li><b>Instructions:</b> we process the data only as these terms and your use of AIBuilder direct, unless the
          law requires otherwise, in which case we tell you first where we may.</li>
        <li><b>Confidentiality and security:</b> only people bound to confidentiality can access it, and we protect it
          with the measures in the <a href="/privacy">privacy policy</a>.</li>
        <li><b>Sub-processors:</b> you allow us to use the service providers listed in the privacy policy. We will
          tell you of any new one by updating that list and by email at least 30 days ahead, and you may object by
          deleting the server or closing your account.</li>
        <li><b>Help:</b> we help you answer requests from players who use their data protection rights, and tell you
          without undue delay if a personal data breach affects your server&apos;s data.</li>
        <li><b>End:</b> when you delete the server, we delete its data, as the privacy policy describes.</li>
        <li><b>Checks:</b> we give you the information you need to show that these duties are met.</li>
      </ul>
      <p>Tell the players on your server that you use AIBuilder.</p>

      <h2>11. Our service</h2>
      <p>AIBuilder, its software and its name belong to us. You may install the plugin on the servers you add to your
        account, for use with AIBuilder. AIBuilder is not affiliated with, endorsed by or connected to Mojang or
        Microsoft. Minecraft is a trademark of Mojang Synergies AB.</p>

      <h2>12. Availability and changes</h2>
      <ul>
        <li>We work to keep AIBuilder running but cannot promise it is never down, for example during maintenance or
          updates. An update stops chats that are running at that moment; you pay only for what was done.</li>
        <li>We improve and change AIBuilder over time. If a change makes what you paid for clearly worse, we email you
          at least 30 days ahead, and you may cancel: we then refund the unused part of your month and your unused
          credits.</li>
        <li>If we close AIBuilder, we tell you at least 30 days ahead and refund the unused part of your month and your
          unused credits.</li>
      </ul>

      <h2>13. Ending the agreement</h2>
      <p>You can stop at any time: cancel your plan on the Billing page, delete your servers, and ask us to delete your
        account by writing to <Mail op={op} />. We may suspend or close an account that seriously or repeatedly breaks
        these terms, or when the law requires it. We tell you why, unless the law forbids it. When we close your
        account we refund your unused credits; if we close it without you having broken these terms, we also refund the
        unused part of your month.</p>

      <h2>14. Liability</h2>
      <p>If you are a consumer, you have the rights the law gives you when a service is not as agreed, and nothing in
        these terms limits them. We are responsible under Danish law for losses we cause, but not for losses caused by
        events outside our control, by your own servers, plugins or AI provider, or by edits you or your members
        asked the AI to make and could have reviewed or reverted.</p>
      <p>If you use AIBuilder as a business, we are not liable for indirect loss such as lost profit or lost data,
        and our total liability is limited to what you paid us in the 12 months before the claim.</p>
      <p>None of these limits apply to losses we cause intentionally or through gross negligence, or where the law
        does not allow liability to be limited.</p>

      <h2>15. Changes to these terms</h2>
      <p>We may update these terms. For changes that matter to you, we email you at least 30 days before they take
        effect. If you do not accept them, you can cancel and close your account before then, and we refund your unused
        credits. Corrections that do not change your rights take effect when we publish them.</p>

      <h2>16. Law and complaints</h2>
      <p>Danish law applies to these terms. If you are a consumer, you also keep the protection of the mandatory rules
        of the country you live in, and you may bring a claim in the courts there. Business customers bring claims in
        the Danish courts.</p>
      <p>If you are unhappy, write to us first at <Mail op={op} />. If we cannot agree, you can complain
        to <a href="https://www.naevneneshus.dk">Nævnenes Hus</a>, Toldboden 2, 8800 Viborg, Denmark. If you live in
        another EU country, the European Consumer Centre Denmark (<a href="https://www.forbrugereuropa.dk">Forbrugereuropa</a>)
        can help.</p>
    </LegalPage>
  );
}
