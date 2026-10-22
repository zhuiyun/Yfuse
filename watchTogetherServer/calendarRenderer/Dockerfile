FROM mcr.microsoft.com/playwright:v1.64.0-noble

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY server.mjs render-scheduler.mjs network-policy.mjs network-guard.mjs ./

ENV YFUSE_RENDERER_HOST=0.0.0.0 \
    YFUSE_RENDERER_PORT=8091
EXPOSE 8091
# Never root: Chromium runs with its sandbox, which needs Playwright's seccomp profile (compose).
USER pwuser
CMD ["node", "server.mjs"]
