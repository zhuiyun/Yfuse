# Matches the playwright version in package.json, so the browser build is the one it expects.
FROM mcr.microsoft.com/playwright:v1.63.0-noble

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY server.mjs render-scheduler.mjs network-guard.mjs ./

ENV YFUSE_RENDERER_HOST=0.0.0.0 \
    YFUSE_RENDERER_PORT=8091
EXPOSE 8091
# Never root: Chromium runs with its sandbox, which needs Playwright's seccomp profile (compose).
USER pwuser
CMD ["node", "server.mjs"]
