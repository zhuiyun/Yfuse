FROM mcr.microsoft.com/playwright:v1.62.1-noble

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY server.mjs render-scheduler.mjs ./

ENV YFUSE_RENDERER_HOST=0.0.0.0 \
    YFUSE_RENDERER_PORT=8091
EXPOSE 8091
USER pwuser
CMD ["node", "server.mjs"]
