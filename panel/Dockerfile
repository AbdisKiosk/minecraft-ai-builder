# The AIBuilder panel: landing page, dashboard, API, the /s/<server>/ proxy and the plugins' /relay WebSocket,
# all in one Node process (server.ts). Configure it with the variables in .env.example.
#   docker build -t aibuilder-panel panel
#   docker run --env-file <file with those variables> -p 3000:3000 aibuilder-panel
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
# public/ has no files yet, so git does not keep it; the next stage copies it. .next/cache only speeds up rebuilds.
RUN mkdir -p public && npm run build && rm -rf .next/cache && npm prune --omit=dev --no-audit --no-fund

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOST=0.0.0.0
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/dist ./dist
COPY --from=build /app/migrations ./migrations
COPY --from=build /app/next.config.ts ./
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:3000/api/health || exit 1
CMD ["node", "dist/server.js"]
