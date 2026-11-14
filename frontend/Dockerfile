FROM node:24-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG SHOP_GOTHAM_PRO=0
ENV SHOP_GOTHAM_PRO=$SHOP_GOTHAM_PRO
RUN npm run build

FROM node:24-alpine AS runtime
WORKDIR /app
ARG SHOP_GOTHAM_PRO=0
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000 SHOP_GOTHAM_PRO=$SHOP_GOTHAM_PRO
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public
USER node
EXPOSE 3000
CMD ["node", "server.js"]
