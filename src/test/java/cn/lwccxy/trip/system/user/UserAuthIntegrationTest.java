package cn.lwccxy.trip.system.user;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.SaManager;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.system.user.domain.form.*;
import cn.lwccxy.trip.system.user.service.UserService;
import cn.lwccxy.trip.system.prompt.service.DefaultOperatorIdentityProvider;
import cn.lwccxy.trip.system.config.service.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import cn.lwccxy.trip.system.user.mapper.PasswordResetLogMapper;
import jakarta.servlet.DispatcherType;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
 "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
 "spring.datasource.driver-class-name=org.h2.Driver",
 "spring.datasource.url=jdbc:h2:mem:userauth;MODE=MySQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.username=sa", "spring.datasource.password=",
 "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:user-auth-schema.sql",
 "sa-token.is-print=false"
})
@AutoConfigureMockMvc
class UserAuthIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired JdbcTemplate jdbc;
 @Autowired UserService service;
 @Autowired CurrentUser currentUser;
 @Autowired DefaultOperatorIdentityProvider identity;
 @Autowired PlatformTransactionManager transactionManager;
 @Autowired PasswordResetLogMapper resetMapper;
 @Autowired ConfigService configService;
 private final String password = UUID.randomUUID().toString();
 private String username;
 private long id;
 @BeforeEach void setup() throws Exception {
  username = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  id = call("POST", "/auth/register", null, Map.of("username", username, "password", password), 200).path("data").path("id").asLong();
 }
 private JsonNode call(String method, String path, String token, Object body, int status) throws Exception {
  var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path);
  if (token != null) request.header("satoken", token);
  if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
  return perform(request, status);
 }
 private JsonNode perform(MockHttpServletRequestBuilder request, int status) throws Exception {
  var response=mvc.perform(request).andReturn().getResponse();
  assertThat(response.getStatus()).isEqualTo(status);
  return response.getContentAsString().isEmpty()?json.createObjectNode():json.readTree(response.getContentAsString());
 }
 private String login(String name, String pass) throws Exception {
  JsonNode response = call("POST", "/auth/login", null, Map.of("username",name,"password",pass),200);
  assertThat(response.path("data").path("expiresIn").asLong()).isBetween(86398L,86400L);
  assertThat(response.toString()).doesNotContain("passwordHash","credentialVersion");
  return response.path("data").path("tokenValue").asText();
 }
 private String admin() throws Exception {
  jdbc.update("UPDATE t_user SET role='ADMIN',credential_version=credential_version+1 WHERE id=?", id);
  return login(username,password);
 }
 private void context(String token) {
  var request=new MockHttpServletRequest();request.addHeader("satoken",token);
  RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
 }
 @AfterEach void clearContext() { RequestContextHolder.resetRequestAttributes(); }
 @Test void registrationValidationHashAndDuplicate() throws Exception {
  call("POST","/auth/register",null,Map.of("username"," "+username.toUpperCase(Locale.ROOT)+" ","password",password),409);
  for (String field:List.of("id","role","mustChangePassword")) {
   call("POST","/auth/register",null,Map.of("username",username+"x","password",password,field,"ADMIN"),400);
  }
  var other=call("POST","/auth/register",null,Map.of("username",username+"x","password",password),200);
  assertThat(other.path("data").path("role").asText()).isEqualTo("USER");
  String hash=jdbc.queryForObject("SELECT password_hash FROM t_user WHERE id=?",String.class,id);
  String second=jdbc.queryForObject("SELECT password_hash FROM t_user WHERE username=?",String.class,username+"x");
  assertThat(hash).isNotEqualTo(second).isNotEqualTo(password);
  assertThat(new BCryptPasswordEncoder().matches(password,hash)).isTrue();
  call("POST","/auth/login",null,Map.of("username",username,"password",UUID.randomUUID().toString()),401);
  call("POST","/auth/login",null,Map.of("username","unknown_account","password",password),401);
 }
 @Test void formBoundariesAndNoSecretToString() throws Exception {
  try(var factory=Validation.buildDefaultValidatorFactory()) {
   var validator=factory.getValidator();
   RegisterForm form=new RegisterForm();form.setUsername(" ABC ");form.setPassword("a".repeat(8));
   assertThat(form.getUsername()).isEqualTo("abc");assertThat(validator.validate(form)).isEmpty();
   for(String p:List.of("a".repeat(64),"中".repeat(24))) {form.setPassword(p);assertThat(validator.validate(form)).isEmpty();}
   for(String p:List.of("a".repeat(7),"a".repeat(65),"中".repeat(25))) {form.setPassword(p);assertThat(validator.validate(form)).isNotEmpty();}
   form.setPassword(password);assertThat(form.toString()).doesNotContain(password);
   form.setUsername("a".repeat(32));assertThat(validator.validate(form)).isEmpty();
   form.setUsername("ab");assertThat(validator.validate(form)).isNotEmpty();
   UserQueryForm query=new UserQueryForm();query.setPageSize(201);assertThat(validator.validate(query)).isNotEmpty();
   query.setPageSize(200);query.setPageNo(0);assertThat(validator.validate(query)).isNotEmpty();
   ResetPasswordForm reset=new ResetPasswordForm();reset.setTemporaryPassword(password);reset.setReason("r".repeat(1000));assertThat(validator.validate(reset)).isEmpty();
   reset.setReason("r".repeat(1001));assertThat(validator.validate(reset)).isNotEmpty();
  }
 }
 @Test void headerOnlyIndependentTokensLogoutAndExpiry() throws Exception {
  String one=login(username,password),two=login(username,password);assertThat(one).isNotEqualTo(two);
  perform(MockMvcRequestBuilders.get("/auth/me").param("satoken",one),401);
  perform(MockMvcRequestBuilders.get("/auth/me").cookie(new jakarta.servlet.http.Cookie("satoken",one)),401);
  call("GET","/auth/me",null,Map.of("satoken",one),401);
  call("POST","/auth/logout",one,null,200);call("GET","/auth/me",one,null,401);call("GET","/auth/me",two,null,200);
  StpUtil.logoutByTokenValue(two);
  call("GET","/auth/me",two,null,401);
 }
 @Test void defaultGatePreciseWhitelistAndTechnicalDispatch() throws Exception {
  call("GET","/auth/me",null,null,401);
  call("POST","/auth/logout",null,null,401);
  call("PUT","/auth/password",null,Map.of("oldPassword",password,"newPassword",password+"x"),401);
  call("POST","/system/user/query",null,Map.of(),401);
  call("POST","/config/query",null,Map.of(),401);
  call("POST","/system/prompt/query",null,Map.of(),401);
  perform(MockMvcRequestBuilders.options("/auth/me"),401);
  mvc.perform(MockMvcRequestBuilders.options("/auth/me").header("Origin","https://example.test").header("Access-Control-Request-Method","GET"));
  assertThat(mvc.perform(MockMvcRequestBuilders.get("/error").with(request -> {
      request.setDispatcherType(DispatcherType.ERROR);
      return request;
  })).andReturn().getResponse().getStatus()).isNotEqualTo(401);
  call("POST","/auth/register",null,Map.of("username","bad!","password",password),400);
  String token=login(username,password);
  call("POST","/system/user/query",token,Map.of("role","ADMIN","userId",id),403);
  call("GET","/config/anything",token,null,403);call("POST","/system/prompt/query",token,Map.of(),403);
 }
 @Test void resetForcedChangeAndAllOldTokensRevoked() throws Exception {
  String old=login(username,password),old2=login(username,password),admin=admin();
  String temp=UUID.randomUUID().toString(),next=UUID.randomUUID().toString();
  call("POST","/system/user/99999999/reset-password",admin,Map.of("temporaryPassword",temp,"reason","verified offline"),404);
  call("POST","/system/user/"+id+"/reset-password",admin,Map.of("temporaryPassword",password,"reason","verified offline"),400);
  call("POST","/system/user/"+id+"/reset-password",admin,Map.of("temporaryPassword",temp,"reason","verified offline"),200);
  for(String token:List.of(old,old2,admin))call("GET","/auth/me",token,null,401);
  assertThat(jdbc.queryForObject("SELECT operator_id FROM t_user_password_reset_log WHERE target_user_id=?",Long.class,id)).isEqualTo(id);
  String restricted=login(username,temp);
  call("GET","/auth/me",restricted,null,200);
  assertThat(call("POST","/system/user/query",restricted,Map.of(),403).path("errorCode").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
  call("PUT","/auth/password",restricted,Map.of("oldPassword",password,"newPassword",next),401);
  call("PUT","/auth/password",restricted,Map.of("oldPassword",temp,"newPassword",temp),400);
  call("PUT","/auth/password",restricted,Map.of("oldPassword",temp,"newPassword",next),200);
  call("GET","/auth/me",restricted,null,401);
  String restored=login(username,next);call("POST","/system/user/query",restored,Map.of("keyword",username,"pageSize",1),200);
 }
 @Test void missingStaleVersionsAndTrustedIdentity() throws Exception {
  String token=login(username,password);context(token);
  assertThat(identity.currentOperatorId()).isEqualTo(Long.toString(id));
  StpUtil.getTokenSession().delete(CurrentUser.CREDENTIAL_VERSION);clearContext();call("GET","/auth/me",token,null,401);
  String other=login(username,password);jdbc.update("UPDATE t_user SET credential_version=credential_version+1 WHERE id=?",id);call("GET","/auth/me",other,null,401);
  String deleted=login(username,password);jdbc.update("DELETE FROM t_user WHERE id=?",id);call("GET","/auth/me",deleted,null,401);
 }
 @Test void stableFilteredPagination() throws Exception {
  String token=admin();
  var page=call("POST","/system/user/query",token,Map.of("keyword",username,"role","ADMIN","pageSize",1),200);
  assertThat(page.path("totalCount").asLong()).isEqualTo(1);assertThat(page.path("totalPage").asLong()).isEqualTo(1);
  assertThat(page.toString()).doesNotContain("passwordHash","credentialVersion");
  call("POST","/system/user/query",token,Map.of("role","ROOT"),400);
  call("POST","/system/user/0/reset-password",token,Map.of("temporaryPassword",password,"reason","verified"),400);
 }
 @Test void concurrentDuplicateRegistration() throws Exception {
  String name=username+"c";var pool=Executors.newFixedThreadPool(2);
  try {
   Callable<String> register=()->{RegisterForm f=new RegisterForm();f.setUsername(name);f.setPassword(password);try {service.register(f);return "OK";}catch(cn.lwccxy.trip.common.exception.BizException e){return e.getErrorCode();}};
   var first=pool.submit(register);var second=pool.submit(register);assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder("OK","USER_ALREADY_EXISTS");
  }finally {pool.shutdownNow();}
 }
}
