package __WIZ_PACKAGE_ROOT__.model.post;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<PostEntity, String> {

    @Query("""
            select post from PostEntity post
            where (:text = ''
                    or lower(post.title) like lower(concat('%', :text, '%'))
                    or lower(post.content) like lower(concat('%', :text, '%')))
              and (:category = '' or lower(post.category) = lower(:category))
            order by post.createdAt desc
            """)
    Page<PostEntity> search(
            @Param("text") String text,
            @Param("category") String category,
            Pageable pageable);

    List<PostEntity> findTop5ByOrderByCreatedAtDesc();

    long countByStatus(PostStatus status);

    @Query("select distinct post.category from PostEntity post where post.category <> '' order by post.category")
    List<String> findDistinctCategories();
}
