package __WIZ_PACKAGE_ROOT__.api;

import java.util.List;

import __WIZ_PACKAGE_ROOT__.api.model.PostModels.PostPage;
import __WIZ_PACKAGE_ROOT__.api.model.PostModels.PostRequest;
import __WIZ_PACKAGE_ROOT__.api.model.PostModels.PostResponse;
import __WIZ_PACKAGE_ROOT__.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    private final PostService posts;

    public PostController(PostService posts) {
        this.posts = posts;
    }

    @GetMapping
    public PostPage search(
            @RequestParam(defaultValue = "") String text,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return posts.search(text, category, page, size);
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return posts.categories();
    }

    @GetMapping("/{id}")
    public PostResponse get(@PathVariable String id) {
        return posts.get(id);
    }

    @PostMapping
    public ResponseEntity<PostResponse> create(
            @Valid @RequestBody PostRequest input,
            HttpServletRequest request) {
        PostResponse post = posts.create(request, input);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(post.id())
                .toUri()).body(post);
    }

    @PutMapping("/{id}")
    public PostResponse update(
            @PathVariable String id,
            @Valid @RequestBody PostRequest input,
            HttpServletRequest request) {
        return posts.update(request, id, input);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        posts.delete(request, id);
        return ResponseEntity.noContent().build();
    }
}
