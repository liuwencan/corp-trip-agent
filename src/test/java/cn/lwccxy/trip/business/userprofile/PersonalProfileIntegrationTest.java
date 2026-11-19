package cn.lwccxy.trip.business.userprofile;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.lwccxy.trip.business.userprofile.controller.PersonalProfileController;
import cn.lwccxy.trip.business.userprofile.domain.entity.UserProfileEntity;
import cn.lwccxy.trip.business.userprofile.mapper.UserProfileMapper;
import cn.lwccxy.trip.business.userprofile.service.PersonalProfileService;
import cn.lwccxy.trip.business.userprofile.service.UserProfileService;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.config.AuthWebConfig;
import cn.lwccxy.trip.common.config.GlobalExceptionHandler;
import cn.lwccxy.trip.system.user.controller.AuthController;
import cn.lwccxy.trip.system.user.mapper.UserMapper;
import cn.lwccxy.trip.system.user.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实鉴权、事务、Mapper 和 Web 上下文验证本人资料操作，不依赖外部服务。 */
@SpringJUnitConfig(PersonalProfileIntegrationTest.TestConfig.class)
@WebAppConfiguration
class PersonalProfileIntegrationTest {
    private static final String PASSWORD = "profile-test-password";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    private static SaTokenContext previousContext;
    private static SaTokenDao previousDao;
    private static SaTokenConfig previousConfig;

    @Autowired private WebApplicationContext context;
    @Autowired private DataSource dataSource;
    @Autowired private UserProfileService agentProfileService;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private String token;

    /** 隔离测试会话存储，确保鉴权验证不连接真实 Redis。 */
    @BeforeAll
    static void initializeAuthentication() {
        previousContext = SaManager.getSaTokenContext();
        previousDao = SaManager.getSaTokenDao();
        previousConfig = SaManager.getConfig();
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setConfig(new SaTokenConfig().setTokenName("satoken").setIsPrint(false));
    }

    /** 恢复全局鉴权配置，避免影响其他模块测试。 */
    @AfterAll
    static void restoreAuthentication() {
        ((SaTokenDaoDefaultImpl) SaManager.getSaTokenDao()).destroy();
        SaManager.setSaTokenContext(previousContext);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setConfig(previousConfig);
    }

    /** 为每个场景初始化两个独立账户并通过真实登录接口取得令牌。 */
    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM user_profile");
        jdbc.update("DELETE FROM t_user");
        jdbc.update("INSERT INTO t_user(id, username, password_hash, nick_name) VALUES (1, 'first_user', ?, '原昵称')", PASSWORD_HASH);
        jdbc.update("INSERT INTO t_user(id, username, password_hash, nick_name) VALUES (2, 'other_user', ?, '其他用户')", PASSWORD_HASH);
        token = login("first_user");
    }

    /** 登录走控制器和真实凭据校验，返回后续请求使用的令牌。 */
    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("tokenValue").asText();
    }

    /** 完整验证创建、局部更新、清空、读取、幂等删除及账户安全字段保持正确。 */
    @Test
    void managesBothTablesWithoutChangingCredentials() throws Exception {
        mvc.perform(get("/user/profile").header("satoken", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileExists").value(false));
        mvc.perform(post("/user/profile").header("satoken", token).contentType("application/json").content("""
                {"nickName":"新昵称","avatar":"https://example.com/avatar.png","baseCity":"南京",
                 "level":"P6","namePinyin":"zhang  san","email":"person@example.com",
                 "chineseName":"张三","idType":1,"idNumber":"TEST-PASSPORT",
                 "phone":"+86 1234567","gender":"M"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileExists").value(true))
                .andExpect(jsonPath("$.data.account.nickName").value("新昵称"))
                .andExpect(jsonPath("$.data.profile.namePinyin").value("ZHANG SAN"))
                .andExpect(jsonPath("$.data.profile.idType").value(1))
                .andExpect(jsonPath("$.data.account.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.account.credentialVersion").doesNotExist());
        mvc.perform(put("/api/user/profile").header("satoken", token).contentType("application/json")
                        .content("{\"email\":\"new@example.com\",\"clearFields\":[\"avatar\",\"phone\",\"idType\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.account.avatar").isEmpty())
                .andExpect(jsonPath("$.data.profile.phone").isEmpty())
                .andExpect(jsonPath("$.data.profile.idType").isEmpty())
                .andExpect(jsonPath("$.data.profile.baseCity").value("南京"));
        // Agent 读取同一张表，前端修改后无须刷新额外的档案缓存。
        assertEquals("new@example.com", agentProfileService.get("1").getEmail());
        mvc.perform(get("/auth/me").header("satoken", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickName").value("新昵称"));
        mvc.perform(delete("/user/profile").header("satoken", token)).andExpect(status().isOk());
        mvc.perform(delete("/user/profile").header("satoken", token)).andExpect(status().isOk());
        mvc.perform(get("/user/profile").header("satoken", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileExists").value(false))
                .andExpect(jsonPath("$.data.account.nickName").isEmpty());
        assertEquals(PASSWORD_HASH, jdbc.queryForObject("SELECT password_hash FROM t_user WHERE id=1", String.class));
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM t_user WHERE id=1", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT credential_version FROM t_user WHERE id=1", Long.class));
        assertEquals("其他用户", jdbc.queryForObject("SELECT nick_name FROM t_user WHERE id=2", String.class));
    }

    /** 非法枚举、越权字段、超长和格式错误都必须在数据库写入前拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"idType\":10}", "{\"idType\":-1}", "{\"idType\":1.5}", "{\"idType\":\"1\"}",
            "{\"gender\":\"OTHER\"}", "{\"gender\":0}", "{\"level\":\"P99\"}",
            "{\"level\":0}", "{\"id\":2}", "{\"userId\":\"2\"}", "{\"role\":\"ADMIN\"}",
            "{\"passwordHash\":\"fake\"}", "{\"credentialVersion\":0}", "{\"username\":\"renamed\"}",
            "{\"mustChangePassword\":false}", "{\"clearFields\":[\"role\"]}",
            "{\"clearFields\":[null]}", "{\"nickName\":\"123456789012345678901\"}",
            "{\"nickName\":\"   \"}", "{\"email\":\"wrong-email\"}",
            "{\"phone\":\"-------\"}", "{\"namePinyin\":\"张三\"}", "{\"avatar\":\"javascript:alert(1)\"}",
            "{}", "{\"nickName\":null}", "{\"email\":\"\"}",
            "{\"nickName\":\"新昵称\",\"clearFields\":[\"nickName\"]}"
    })
    void rejectsInvalidOrPrivilegedInputs(String body) throws Exception {
        mvc.perform(post("/user/profile").header("satoken", token).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_profile", Integer.class));
        assertEquals("原昵称", jdbc.queryForObject("SELECT nick_name FROM t_user WHERE id=1", String.class));
    }

    /** 首次更新返回不存在，重复创建返回冲突，前端可据此选择正确的保存操作。 */
    @Test
    void reportsLifecycleErrors() throws Exception {
        mvc.perform(put("/user/profile").header("satoken", token).contentType("application/json")
                        .content("{\"baseCity\":\"南京\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
        mvc.perform(post("/user/profile").header("satoken", token).contentType("application/json")
                        .content("{\"baseCity\":\"南京\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/user/profile").header("satoken", token).contentType("application/json")
                        .content("{\"nickName\":\"不应保存\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PROFILE_ALREADY_EXISTS"));
        assertEquals("原昵称", jdbc.queryForObject("SELECT nick_name FROM t_user WHERE id=1", String.class));
    }

    /** 真实登录拦截器拒绝未登录、过期凭据和必须修改密码的会话。 */
    @Test
    void enforcesAuthenticationAndCredentialState() throws Exception {
        mvc.perform(get("/user/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/profile/options")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE t_user SET must_change_password=TRUE WHERE id=1");
        mvc.perform(get("/user/profile").header("satoken", token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
        jdbc.update("UPDATE t_user SET credential_version=1 WHERE id=1");
        mvc.perform(delete("/user/profile").header("satoken", token)).andExpect(status().isUnauthorized());
    }

    /** 不同用户的查询与删除必须始终隔离。 */
    @Test
    void isolatesOwners() throws Exception {
        jdbc.update("INSERT INTO user_profile(user_id, base_city) VALUES ('2', '上海')");
        mvc.perform(get("/user/profile").header("satoken", token))
                .andExpect(jsonPath("$.data.profileExists").value(false));
        mvc.perform(delete("/user/profile").header("satoken", token)).andExpect(status().isOk());
        String otherToken = login("other_user");
        mvc.perform(get("/api/user/profile").header("satoken", otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profile.baseCity").value("上海"));
    }

    /** 两张表的写入必须原子提交，账户更新失败时撤销此前创建的档案。 */
    @Test
    void rollsBackBothTablesOnDatabaseFailure() throws Exception {
        // 测试专用约束模拟持久化阶段失败，不更改实际数据库结构。
        jdbc.execute("ALTER TABLE t_user ADD CONSTRAINT test_reject_nickname CHECK (nick_name <> '触发失败')");
        try {
            mvc.perform(post("/user/profile").header("satoken", token).contentType("application/json")
                            .content("{\"nickName\":\"触发失败\",\"baseCity\":\"南京\"}"))
                    .andExpect(status().isInternalServerError());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_profile", Integer.class));
            assertEquals("原昵称", jdbc.queryForObject("SELECT nick_name FROM t_user WHERE id=1", String.class));
        } finally {
            jdbc.execute("ALTER TABLE t_user DROP CONSTRAINT test_reject_nickname");
        }
    }

    /** 下拉选项使用业务编码，Agent 写入也必须遵循相同的枚举约束。 */
    @Test
    void sharesEnumRulesWithAgentTools() throws Exception {
        mvc.perform(get("/api/user/profile/options").header("satoken", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.idTypes.length()").value(10))
                .andExpect(jsonPath("$.data.idTypes[0].code").value(0))
                .andExpect(jsonPath("$.data.idTypes[0].label").value("身份证"))
                .andExpect(jsonPath("$.data.levels.length()").value(4))
                .andExpect(jsonPath("$.data.editableFields.length()").value(11));
        UserProfileEntity changes = new UserProfileEntity();
        changes.setLevel("P99");
        assertThrows(IllegalArgumentException.class, () -> agentProfileService.updatePartial("1", changes));
        changes.setLevel("p6");
        changes.setGender("f");
        changes.setIdType(9);
        changes.setNamePinyin("zhang san");
        agentProfileService.updatePartial("1", changes);
        assertEquals("P6", agentProfileService.get("1").getLevel());
        assertEquals("F", agentProfileService.get("1").getGender());
        assertEquals("ZHANG SAN", agentProfileService.get("1").getNamePinyin());
    }

    /** 仅启动本功能需要的真实 Web、鉴权和数据库组件，避免访问外部模型及真实数据库。 */
    @TestConfiguration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackageClasses = {UserMapper.class, UserProfileMapper.class})
    @Import({PersonalProfileController.class, PersonalProfileService.class, UserProfileService.class,
            CurrentUser.class, UserService.class, AuthController.class, AuthWebConfig.class, GlobalExceptionHandler.class})
    static class TestConfig {
        /** 创建与真实表字段一致的独立测试数据库。 */
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                    .setName("profile;MODE=MySQL").addScript("personal-profile-schema.sql").build();
        }

        /** 启用 MyBatis-Plus 的实际映射与下划线转驼峰规则。 */
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        /** 为跨表写入启用真实事务及失败回滚。 */
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /** 提供与生产接口一致的 Jakarta Validation 校验器。 */
        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }
}
