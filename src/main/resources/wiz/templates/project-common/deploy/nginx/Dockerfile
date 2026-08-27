FROM nginx:1.29-alpine

COPY public /usr/share/nginx/html
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
RUN chmod -R a=rX /usr/share/nginx/html
