package com.smy101.reader;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 集成测试基类:唯一测试 seam = 真实 HTTP × Testcontainers(spec · Testing Decisions)。
 * <p>
 * 拉起与生产同款 pgvector/pgvector:pg18 容器(@ServiceConnection 由子类注解完成),
 * 应用连测试库跑真实请求;云 PG 永不进测试(D-11 口径)。
 * <p>
 * 容器与 storage 目录按 JVM 单例复用,避免每个测试类重复起容器;
 * 每个测试前清空 book/chapter 表与 storage 目录内容,保证互不串扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    /** 测试用 token 与 application.yml 本地开发默认值一致 */
    protected static final String TOKEN = "reader-dev-token";

    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("reader_test")
            .withUsername("test")
            .withPassword("test");

    protected static final Path STORAGE_ROOT;

    static {
        POSTGRES.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("reader-test-storage");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试 storage 目录", e);
        }
    }

    @DynamicPropertySource
    static void registerTestProperties(DynamicPropertyRegistry registry) {
        registry.add("reader.storage.root", () -> STORAGE_ROOT.toString());
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    /** 读 fixtures 目录下的 EPUB(正常/损坏/DRM/字体混淆)。 */
    protected static byte[] readFixture(String name) throws IOException {
        return new ClassPathResource("fixtures/" + name).getInputStream().readAllBytes();
    }

    @BeforeEach
    void cleanState() throws IOException {
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM chat_session");
        jdbc.update("DELETE FROM highlight");
        jdbc.update("DELETE FROM reading_progress");
        jdbc.update("DELETE FROM chapter");
        jdbc.update("DELETE FROM book");
        jdbc.update("DELETE FROM model_settings");
        try (Stream<Path> entries = Files.list(STORAGE_ROOT)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                deleteRecursively(entry);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
