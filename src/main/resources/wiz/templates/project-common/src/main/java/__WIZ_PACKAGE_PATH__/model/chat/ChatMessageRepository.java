package __WIZ_PACKAGE_ROOT__.model.chat;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<ChatMessageEntity> findByIdGreaterThanOrderByIdAsc(long id);
}
