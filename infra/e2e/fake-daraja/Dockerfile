# Fake Daraja for the end-to-end suite only (ADR 0012); never part of the normal stack.
FROM node:24.11.1-alpine3.22
WORKDIR /app
COPY server.mjs .
USER node
EXPOSE 8095
HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8095/health || exit 1
CMD ["node", "server.mjs"]
