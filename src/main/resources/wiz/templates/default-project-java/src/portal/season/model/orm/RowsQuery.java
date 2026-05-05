import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public record RowsQuery(
        Integer page,
        Integer dump,
        String orderBy,
        String order,
        String fields,
        String like,
        Map<String, Object> where) {

    public RowsQuery {
        where = where == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(where));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RowsQuery where(Map<String, Object> where) {
        return builder().where(where).build();
    }

    public static class Builder {

        private Integer page;
        private Integer dump;
        private String orderBy;
        private String order;
        private String fields;
        private String like;
        private final Map<String, Object> where = new LinkedHashMap<>();

        public Builder page(Integer page) {
            this.page = page;
            return this;
        }

        public Builder dump(Integer dump) {
            this.dump = dump;
            return this;
        }

        public Builder orderBy(String orderBy) {
            this.orderBy = orderBy;
            return this;
        }

        public Builder order(String order) {
            this.order = order;
            return this;
        }

        public Builder fields(String fields) {
            this.fields = fields;
            return this;
        }

        public Builder like(String like) {
            this.like = like;
            return this;
        }

        public Builder where(String key, Object value) {
            this.where.put(key, value);
            return this;
        }

        public Builder where(Map<String, Object> values) {
            if (values != null) {
                this.where.putAll(values);
            }
            return this;
        }

        public RowsQuery build() {
            return new RowsQuery(page, dump, orderBy, order, fields, like, where);
        }
    }
}