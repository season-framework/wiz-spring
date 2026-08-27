package __WIZ_PACKAGE_ROOT__.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import __WIZ_PACKAGE_ROOT__.domain.PostEntity;
import __WIZ_PACKAGE_ROOT__.domain.PostStatus;
import __WIZ_PACKAGE_ROOT__.domain.UserEntity;
import __WIZ_PACKAGE_ROOT__.domain.UserRole;
import __WIZ_PACKAGE_ROOT__.repository.PostRepository;
import __WIZ_PACKAGE_ROOT__.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SampleDataInitializer implements ApplicationRunner {

    private final UserRepository users;
    private final PostRepository posts;
    private final PasswordEncoder passwords;

    public SampleDataInitializer(UserRepository users, PostRepository posts, PasswordEncoder passwords) {
        this.users = users;
        this.posts = posts;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        Instant now = Instant.now();
        UserEntity admin = seedUser(
                "admin@example.com", "admin1234", "관리자", "010-0000-0000", UserRole.ADMIN,
                now.minus(40, ChronoUnit.DAYS));
        seedUser(
                "alice@example.com", "alice1234", "Alice Kim", "010-1000-0001", UserRole.USER,
                now.minus(30, ChronoUnit.DAYS));
        seedUser(
                "bob@example.com", "bob12345", "Bob Park", "010-1000-0002", UserRole.USER,
                now.minus(24, ChronoUnit.DAYS));
        seedUser(
                "carol@example.com", "carol123", "Carol Lee", "010-1000-0003", UserRole.EDITOR,
                now.minus(18, ChronoUnit.DAYS));
        seedUser(
                "dave@example.com", "dave1234", "Dave Choi", "010-1000-0004", UserRole.VIEWER,
                now.minus(12, ChronoUnit.DAYS));

        if (posts.count() == 0) {
            posts.save(new PostEntity(
                    UUID.randomUUID().toString(),
                    "Spring WIZ 시작하기",
                    "표준 Spring 백엔드와 선택한 프론트엔드 템플릿을 독립적으로 개발하고 빌드하는 샘플입니다.",
                    "공지사항",
                    admin.getId(),
                    admin.getName(),
                    PostStatus.PUBLISHED,
                    now.minus(3, ChronoUnit.DAYS)));
            posts.save(new PostEntity(
                    UUID.randomUUID().toString(),
                    "API 작성 가이드",
                    "@ApiController를 사용하면 중앙 API prefix와 버전 정책이 모든 컨트롤러에 적용됩니다.",
                    "가이드",
                    admin.getId(),
                    admin.getName(),
                    PostStatus.PUBLISHED,
                    now.minus(2, ChronoUnit.DAYS)));
            posts.save(new PostEntity(
                    UUID.randomUUID().toString(),
                    "팀 초대 기능 점검",
                    "멤버 초대와 역할별 화면 흐름을 확인하기 위한 임시 게시물입니다.",
                    "자유게시판",
                    admin.getId(),
                    admin.getName(),
                    PostStatus.DRAFT,
                    now.minus(1, ChronoUnit.DAYS)));
        }
    }

    private UserEntity seedUser(
            String email,
            String password,
            String name,
            String mobile,
            UserRole role,
            Instant createdAt) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> users.save(new UserEntity(
                UUID.randomUUID().toString(),
                email,
                passwords.encode(password),
                name,
                mobile,
                role,
                createdAt)));
    }
}
