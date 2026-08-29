package __WIZ_PACKAGE_ROOT__.controller;

import java.util.List;

import __WIZ_PACKAGE_ROOT__.model.Struct;
import __WIZ_PACKAGE_ROOT__.model.post.PostStruct.PageView;
import __WIZ_PACKAGE_ROOT__.model.post.PostStruct.View;
import __WIZ_PACKAGE_ROOT__.web.ApiController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@ApiController("/posts")
public class PostController {

    private final Struct struct;

    public PostController(Struct struct) {
        this.struct = struct;
    }

    @GetMapping
    public PageView search(
            @RequestParam(defaultValue = "") String text,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return struct.post().search(text, category, page, size);
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return struct.post().categories();
    }

    @GetMapping("/{id}")
    public View get(@PathVariable String id) {
        return struct.post().get(id);
    }

    @PostMapping
    public ResponseEntity<View> create(@Valid @RequestBody PostRequest input) {
        View post = struct.post().create(input.title(), input.content(), input.category(), input.status());
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(post.id())
                .toUri()).body(post);
    }

    @PutMapping("/{id}")
    public View update(@PathVariable String id, @Valid @RequestBody PostRequest input) {
        return struct.post().update(id, input.title(), input.content(), input.category(), input.status());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        struct.post().delete(id);
        return ResponseEntity.noContent().build();
    }

    public record PostRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10_000) String content,
            @Size(max = 60) String category,
            @Size(max = 20) String status) {
    }
}
