export const ROLES = ['owner', 'admin', 'builder', 'viewer'] as const;
export type Role = (typeof ROLES)[number];

const RANK: Record<Role, number> = { owner: 4, admin: 3, builder: 2, viewer: 1 };

export function isRole(s: unknown): s is Role {
  return typeof s === 'string' && (ROLES as readonly string[]).includes(s);
}

/** Whether `role` is at least `needed`. */
export function atLeast(role: Role | null | undefined, needed: Role): boolean {
  return !!role && RANK[role] >= RANK[needed];
}

/**
 * The roles `actor` may hand out (in an invite or a role change). Owners give admin and below; admins give
 * builder and viewer. Nobody gives owner.
 */
export function grantable(actor: Role): Role[] {
  if (actor === 'owner') return ['admin', 'builder', 'viewer'];
  if (actor === 'admin') return ['builder', 'viewer'];
  return [];
}

/** Whether `actor` may change or remove a member who has `target`: only members ranked strictly below. */
export function canManage(actor: Role, target: Role): boolean {
  return actor !== 'viewer' && actor !== 'builder' && RANK[actor] > RANK[target];
}

/** What each role may do, in words, for the UI. */
export const ROLE_TEXT: Record<Role, string> = {
  owner: 'everything, including deleting the server',
  admin: 'approve drafts, revert edits, invite and manage builders and viewers',
  builder: 'build with Claude Code and scripts, promote regions',
  viewer: 'see jobs, history and drafts',
};
