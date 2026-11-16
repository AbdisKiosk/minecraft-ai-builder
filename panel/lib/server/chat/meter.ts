import type Anthropic from '@anthropic-ai/sdk';

// Charging a chat on credits for what its requests to the model use (lib/server/credits.ts keeps the ledger).

/** The part of the SDK's message stream the meter reads. */
export interface Metered {
  finalMessage(): Promise<Anthropic.Message>;
  /** The answer as far as it has arrived. */
  readonly currentMessage: Anthropic.Message | undefined;
}

/**
 * The stream's final message, after `charge` was called for what the request used. A request that was stopped or broke
 * off is charged too, from what had arrived: Anthropic bills the prompt it read and the answer it wrote until then.
 * A charge that fails makes the request fail, so a turn does not go on unpaid.
 */
export async function metered(stream: Metered, charge: (usage: Anthropic.Usage) => Promise<void>): Promise<Anthropic.Message> {
  let message: Anthropic.Message;
  try {
    message = await stream.finalMessage();
  } catch (e) {
    const partial = stream.currentMessage?.usage;
    if (partial) await charge(partial).catch(err => console.error('chat: could not charge a request that stopped early', err));
    throw e;
  }
  await charge(message.usage);
  return message;
}
