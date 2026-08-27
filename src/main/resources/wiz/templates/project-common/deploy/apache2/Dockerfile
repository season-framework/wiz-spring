FROM httpd:2.4-alpine

RUN apk add --no-cache gettext \
    && sed -i 's/^#LoadModule proxy_module/LoadModule proxy_module/' /usr/local/apache2/conf/httpd.conf \
    && sed -i 's/^#LoadModule proxy_http_module/LoadModule proxy_http_module/' /usr/local/apache2/conf/httpd.conf \
    && sed -i 's/^#LoadModule proxy_wstunnel_module/LoadModule proxy_wstunnel_module/' /usr/local/apache2/conf/httpd.conf \
    && sed -i 's/^#LoadModule rewrite_module/LoadModule rewrite_module/' /usr/local/apache2/conf/httpd.conf \
    && sed -i 's/^#LoadModule headers_module/LoadModule headers_module/' /usr/local/apache2/conf/httpd.conf \
    && printf '\nInclude conf/extra/wiz.conf\n' >> /usr/local/apache2/conf/httpd.conf
COPY public /usr/local/apache2/htdocs
COPY deploy/apache2/000-default.conf.template /usr/local/apache2/conf/extra/wiz.conf.template
COPY deploy/apache2/entrypoint.sh /usr/local/bin/wiz-apache-entrypoint
RUN chmod -R a=rX /usr/local/apache2/htdocs \
    && chmod 0755 /usr/local/bin/wiz-apache-entrypoint
ENTRYPOINT ["wiz-apache-entrypoint"]
CMD ["httpd-foreground"]
