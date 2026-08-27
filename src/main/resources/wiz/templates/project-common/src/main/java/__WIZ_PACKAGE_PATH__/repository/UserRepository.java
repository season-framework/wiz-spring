package __WIZ_PACKAGE_ROOT__.repository;

import java.util.List;
import java.util.Optional;

import __WIZ_PACKAGE_ROOT__.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<UserEntity> findAllByOrderByCreatedAtAsc();
}
