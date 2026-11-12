# The AIBuilder panel: landing page, dashboard, API, the /s/<server>/ proxy and the plugins' /relay WebSocket,
# all in one Node process (server.ts), plus the plugin jar it offers for download. Configure it with the variables
# in panel/.env.example. The build context is the repository root, because the jar is built from plugin/:
#   docker build -f panel/Dockerfile -t aibuilder-panel .
#   docker run --env-file <file with those variables> -p 3000:3000 aibuilder-panel
FROM eclipse-temurin:21-jdk AS plugin
WORKDIR /src
# The wrapper first, so the Gradle download is cached until the wrapper changes.
COPY plugin/gradlew ./
COPY plugin/gradle ./gradle
RUN ./gradlew --no-daemon --version
COPY plugin/ ./
# The commit goes into the jar's name, and so into every server's download; .git is not in the build context, so
# pass it: --build-arg GIT_COMMIT=$(git rev-parse --short HEAD). Coolify passes SOURCE_COMMIT by itself.
ARG GIT_COMMIT= SOURCE_COMMIT=
RUN GIT_COMMIT="${GIT_COMMIT:-$SOURCE_COMMIT}" ./gradlew --no-daemon :core:shadowJar

FROM node:24-alpine AS build
WORKDIR /app
COPY panel/package.json panel/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY panel/ ./
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
COPY --from=plugin /src/core/build/libs/ ./plugin-jar/
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:3000/api/health || exit 1
CMD ["node", "dist/server.js"]
