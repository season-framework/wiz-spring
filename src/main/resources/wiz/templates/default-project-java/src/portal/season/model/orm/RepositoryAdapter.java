import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public interface RepositoryAdapter {

    TableSchema schema();

    default Map<String, Object> toDto(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> dto = new LinkedHashMap<>(row);
        schema().privateColumns().forEach(dto::remove);
        return Collections.unmodifiableMap(dto);
    }
}