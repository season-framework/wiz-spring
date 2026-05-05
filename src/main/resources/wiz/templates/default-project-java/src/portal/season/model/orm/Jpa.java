import com.wiz.runtime.WizContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

public final class Jpa {

    private final AnnotationConfigApplicationContext context;
    private final EntityManager entityManager;

    public Jpa(WizContext wiz) {
        ClassLoader projectLoader = getClass().getClassLoader();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.setBeanClassLoader(projectLoader);
        this.context = new AnnotationConfigApplicationContext(beanFactory);
        this.context.setClassLoader(projectLoader);
        this.context.registerBean(WizContext.class, () -> wiz);
        this.context.register(JpaConfig.class);

        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(projectLoader);
        try {
            this.context.refresh();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }

        EntityManagerFactory factory = this.context.getBean(EntityManagerFactory.class);
        this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        wiz.onCleanup(this.context::close);
    }

    public EntityManager entityManager() {
        return entityManager;
    }

    public TransactionTemplate transaction() {
        return context.getBean(TransactionTemplate.class);
    }
}
