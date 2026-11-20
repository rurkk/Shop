FROM nginx:1.30-alpine AS proxy
RUN rm /etc/nginx/conf.d/default.conf
COPY limits.conf /etc/nginx/conf.d/limits.conf
COPY routes.conf api.conf proxy.conf /etc/nginx/

FROM proxy AS local
COPY local.conf /etc/nginx/conf.d/shop.conf

FROM proxy AS production
COPY production.conf.template /etc/nginx/templates/shop.conf.template
