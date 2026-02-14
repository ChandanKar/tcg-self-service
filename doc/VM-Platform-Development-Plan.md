# VM Self-Service Platform - Developer Implementation Plan

**Version:** 1.0  
**Timeline:** 22-24 weeks  
**Target:** Production-ready enterprise VM management platform

---

## Table of Contents
1. [Project Setup](#phase-0-project-setup-week-1)
2. [Authentication & Users](#phase-1-authentication--user-management-weeks-2-3)
3. [Environment & Hierarchy](#phase-2-environment--vm-hierarchy-weeks-4-5)
4. [Dependency Engine](#phase-3-dependency-engine-critical-weeks-6-7)
5. [Lock Management](#phase-4-lock-management-week-8)
6. [Cloud Integration](#phase-5-cloud-provider-integration-weeks-9-10)
7. [VM Operations](#phase-6-vm-operations--orchestration-weeks-11-12)
8. [Governance & Audit](#phase-7-governance--audit-weeks-13-14)
9. [Monitoring & Drift](#phase-8-monitoring--state-drift-weeks-15-16)
10. [Frontend](#phase-9-frontend-development-weeks-17-20)
11. [Integration Testing](#phase-10-integration--e2e-testing-week-21)
12. [Production](#phase-11-production-readiness-weeks-22-23)

---

## Phase 0: Project Setup (Week 1)

### Objectives
- Set up development environment
- Initialize codebase structure
- Configure databases and tooling

### Tasks

#### 1.1 Repository Setup
```bash
# Initialize repository
git init vm-platform
cd vm-platform

# Create branch strategy
git checkout -b develop
git checkout -b main
```

**Folder Structure:**
```
vm-platform/
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/company/vmplatform/
│   │   │   │   ├── config/           # Spring Boot configs
│   │   │   │   ├── controller/       # REST controllers
│   │   │   │   ├── service/          # Business logic
│   │   │   │   ├── repository/       # JPA repositories
│   │   │   │   ├── model/            # Entities
│   │   │   │   ├── dto/              # Data transfer objects
│   │   │   │   ├── security/         # Auth & authorization
│   │   │   │   ├── cloud/            # Cloud provider integrations
│   │   │   │   ├── exception/        # Custom exceptions
│   │   │   │   └── util/             # Utilities
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-dev.yml
│   │   │       ├── application-prod.yml
│   │   │       └── db/migration/
│   │   │           └── V1__initial_schema.sql
│   │   └── test/
│   ├── pom.xml                       # Maven dependencies
│   └── Dockerfile
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── services/
│   │   ├── store/
│   │   └── utils/
│   ├── package.json
│   └── Dockerfile
├── docs/
│   ├── api/
│   ├── architecture/
│   └── deployment/
├── scripts/
│   ├── seed-data.sql
│   └── local-setup.sh
├── docker-compose.yml
└── README.md
```

#### 1.2 Backend Stack Setup (Java/Spring Boot)
**Dependencies (pom.xml):**
```xml
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    
    <!-- Azure AD OAuth2 -->
    <dependency>
        <groupId>com.azure.spring</groupId>
        <artifactId>spring-cloud-azure-starter-active-directory</artifactId>
    </dependency>
    
    <!-- Database -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    
    <!-- Cloud SDKs -->
    <dependency>
        <groupId>com.amazonaws</groupId>
        <artifactId>aws-java-sdk-ec2</artifactId>
    </dependency>
    <dependency>
        <groupId>com.azure</groupId>
        <artifactId>azure-resourcemanager-compute</artifactId>
    </dependency>
    <dependency>
        <groupId>com.google.cloud</groupId>
        <artifactId>google-cloud-compute</artifactId>
    </dependency>
    
    <!-- Utilities -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
    </dependency>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
    </dependency>
    
    <!-- Testing -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

#### 1.3 Database Setup
**Docker Compose (docker-compose.yml):**
```yaml
version: '3.8'
services:
  postgres:
    image: postgres:15
    container_name: vm-platform-db
    environment:
      POSTGRES_DB: vmplatform
      POSTGRES_USER: vmadmin
      POSTGRES_PASSWORD: dev_password_change_me
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./scripts/seed-data.sql:/docker-entrypoint-initdb.d/seed.sql
  
  redis:
    image: redis:7-alpine
    container_name: vm-platform-redis
    ports:
      - "6379:6379"

volumes:
  postgres_data:
```

**Run Database:**
```bash
docker-compose up -d postgres
```

#### 1.4 Application Configuration
**application.yml:**
```yaml
spring:
  application:
    name: vm-platform
  datasource:
    url: jdbc:postgresql://localhost:5432/vmplatform
    username: vmadmin
    password: dev_password_change_me
  jpa:
    hibernate:
      ddl-auto: validate  # Flyway handles schema
    show-sql: true
  flyway:
    enabled: true
    baseline-on-migrate: true

  cloud:
    azure:
      active-directory:
        enabled: true
        tenant-id: ${AZURE_TENANT_ID}
        client-id: ${AZURE_CLIENT_ID}
        client-secret: ${AZURE_CLIENT_SECRET}

logging:
  level:
    com.company.vmplatform: DEBUG
    org.springframework.security: DEBUG

server:
  port: 8080
```

#### 1.5 Frontend Stack Setup (React + TypeScript)
**package.json:**
```json
{
  "name": "vm-platform-frontend",
  "version": "1.0.0",
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0",
    "react-router-dom": "^6.20.0",
    "axios": "^1.6.0",
    "@tanstack/react-query": "^5.0.0",
    "zustand": "^4.4.0",
    "tailwindcss": "^3.3.0",
    "@headlessui/react": "^1.7.0",
    "@heroicons/react": "^2.1.0",
    "react-hot-toast": "^2.4.1"
  },
  "devDependencies": {
    "@types/react": "^18.2.0",
    "@types/react-dom": "^18.2.0",
    "typescript": "^5.3.0",
    "vite": "^5.0.0",
    "@vitejs/plugin-react": "^4.2.0"
  }
}
```

### Deliverables
- ✅ Repository with branch strategy
- ✅ Backend project structure
- ✅ Frontend project structure
- ✅ Docker Compose for local dev
- ✅ Database running with schema V1
- ✅ README with setup instructions

### Definition of Done
- [ ] `docker-compose up` starts all services
- [ ] Backend starts successfully on port 8080
- [ ] Frontend dev server runs on port 3000
- [ ] Database migrations apply successfully
- [ ] CI/CD pipeline configured (GitHub Actions / GitLab CI)

---

## Phase 1: Authentication & User Management (Weeks 2-3)

### Objectives
- Implement Azure AD SSO
- Auto-register users on first login
- Build user management APIs

### Tasks

#### 2.1 Azure AD Integration

**Security Config (SecurityConfig.java):**
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .csrf(csrf -> csrf.disable());
        
        return http.build();
    }
    
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new AzureAdGrantedAuthoritiesConverter());
        return converter;
    }
}
```

**Custom JWT Converter (AzureAdGrantedAuthoritiesConverter.java):**
```java
public class AzureAdGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        
        // Extract roles from Azure AD token
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        
        return authorities;
    }
}
```

#### 2.2 User Auto-Registration

**User Entity (User.java):**
```java
@Entity
@Table(name = "user")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
    
    @Id
    @Column(name = "user_id", length = 36)
    private String userId;
    
    @Column(unique = true, nullable = false)
    private String email;
    
    @Column(name = "display_name", nullable = false)
    private String displayName;
    
    @Column(name = "azure_ad_object_id", unique = true, nullable = false)
    private String azureAdObjectId;
    
    @Column(nullable = false)
    private Boolean admin = false;
    
    @Column(name = "env_admin", nullable = false)
    private Boolean envAdmin = false;
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;
    
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;
    
    @Column(name = "last_login_at")
    private Timestamp lastLoginAt;
}
```

**User Service (UserService.java):**
```java
@Service
@Slf4j
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    @Transactional
    public User registerOrUpdateUser(String azureAdObjectId, String email, String displayName) {
        Optional<User> existingUser = userRepository.findByAzureAdObjectId(azureAdObjectId);
        
        if (existingUser.isPresent()) {
            // Update last login
            User user = existingUser.get();
            user.setLastLoginAt(Timestamp.from(Instant.now()));
            log.info("User {} logged in", email);
            return userRepository.save(user);
        } else {
            // Auto-register new user
            User newUser = new User();
            newUser.setUserId(UUID.randomUUID().toString());
            newUser.setEmail(email);
            newUser.setDisplayName(displayName);
            newUser.setAzureAdObjectId(azureAdObjectId);
            newUser.setAdmin(false);
            newUser.setEnvAdmin(false);
            newUser.setIsActive(true);
            newUser.setLastLoginAt(Timestamp.from(Instant.now()));
            
            log.info("Auto-registered new user: {}", email);
            return userRepository.save(newUser);
        }
    }
}
```

**Authentication Filter (UserRegistrationFilter.java):**
```java
@Component
public class UserRegistrationFilter extends OncePerRequestFilter {
    
    @Autowired
    private UserService userService;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) 
            throws ServletException, IOException {
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) auth.getPrincipal();
            
            String azureObjectId = jwt.getClaimAsString("oid");
            String email = jwt.getClaimAsString("preferred_username");
            String displayName = jwt.getClaimAsString("name");
            
            // Register or update user
            userService.registerOrUpdateUser(azureObjectId, email, displayName);
        }
        
        filterChain.doFilter(request, response);
    }
}
```

#### 2.3 User Management APIs

**User Controller (UserController.java):**
```java
@RestController
@RequestMapping("/api/users")
@Slf4j
public class UserController {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private AuthorizationService authService;
    
    // Get current user profile
    @GetMapping("/me")
    public ResponseEntity<UserDTO> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String azureObjectId = jwt.getClaimAsString("oid");
        User user = userService.getUserByAzureObjectId(azureObjectId);
        return ResponseEntity.ok(UserDTO.fromEntity(user));
    }
    
    // List all users (admin only)
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserDTO>> listUsers(
            @RequestParam(required = false) Boolean admin,
            @RequestParam(required = false) Boolean envAdmin,
            @RequestParam(required = false) Boolean isActive) {
        
        List<User> users = userService.listUsers(admin, envAdmin, isActive);
        return ResponseEntity.ok(users.stream().map(UserDTO::fromEntity).toList());
    }
    
    // Promote user to environment admin (global admin only)
    @PostMapping("/{userId}/promote-env-admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> promoteToEnvAdmin(@PathVariable String userId, @AuthenticationPrincipal Jwt jwt) {
        String currentUserId = authService.getCurrentUserId(jwt);
        userService.promoteToEnvAdmin(userId, currentUserId);
        return ResponseEntity.ok().build();
    }
    
    // Promote user to global admin (global admin only)
    @PostMapping("/{userId}/promote-admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> promoteToAdmin(@PathVariable String userId, @AuthenticationPrincipal Jwt jwt) {
        String currentUserId = authService.getCurrentUserId(jwt);
        userService.promoteToAdmin(userId, currentUserId);
        return ResponseEntity.ok().build();
    }
    
    // Deactivate user (admin only)
    @PostMapping("/{userId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateUser(@PathVariable String userId) {
        userService.deactivateUser(userId);
        return ResponseEntity.ok().build();
    }
}
```

**UserDTO:**
```java
@Data
@Builder
public class UserDTO {
    private String userId;
    private String email;
    private String displayName;
    private Boolean admin;
    private Boolean envAdmin;
    private Boolean isActive;
    private Timestamp lastLoginAt;
    
    public static UserDTO fromEntity(User user) {
        return UserDTO.builder()
            .userId(user.getUserId())
            .email(user.getEmail())
            .displayName(user.getDisplayName())
            .admin(user.getAdmin())
            .envAdmin(user.getEnvAdmin())
            .isActive(user.getIsActive())
            .lastLoginAt(user.getLastLoginAt())
            .build();
    }
}
```

#### 2.4 Access Request Workflow

**Environment Access Entity:**
```java
@Entity
@Table(name = "environment_access")
@Data
public class EnvironmentAccess {
    @Id
    @Column(name = "access_id", length = 36)
    private String accessId;
    
    @ManyToOne
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;
    
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", length = 20, nullable = false)
    private AccessLevel accessLevel; // VIEWER, USER, ADMIN
    
    @ManyToOne
    @JoinColumn(name = "granted_by_user_id", nullable = false)
    private User grantedBy;
    
    @Column(name = "granted_at", nullable = false)
    @CreationTimestamp
    private Timestamp grantedAt;
    
    @Column(name = "expires_at")
    private Timestamp expiresAt;
    
    @Column(name = "revoked_at")
    private Timestamp revokedAt;
    
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AccessStatus status; // PENDING, ACTIVE, REVOKED, EXPIRED
    
    @Column(columnDefinition = "TEXT")
    private String notes;
}
```

**Access Request Controller:**
```java
@RestController
@RequestMapping("/api/access-requests")
public class AccessRequestController {
    
    @Autowired
    private AccessRequestService accessRequestService;
    
    // User requests access to environment
    @PostMapping
    public ResponseEntity<AccessRequestDTO> requestAccess(
            @RequestBody CreateAccessRequestDTO request,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        AccessRequest created = accessRequestService.createRequest(userId, request);
        return ResponseEntity.ok(AccessRequestDTO.fromEntity(created));
    }
    
    // Admin/Env-Admin approves request
    @PostMapping("/{requestId}/approve")
    public ResponseEntity<Void> approveRequest(
            @PathVariable String requestId,
            @RequestBody ApprovalDTO approval,
            @AuthenticationPrincipal Jwt jwt) {
        
        String adminUserId = authService.getCurrentUserId(jwt);
        accessRequestService.approveRequest(requestId, adminUserId, approval);
        return ResponseEntity.ok().build();
    }
    
    // Admin/Env-Admin denies request
    @PostMapping("/{requestId}/deny")
    public ResponseEntity<Void> denyRequest(
            @PathVariable String requestId,
            @RequestBody DenialDTO denial,
            @AuthenticationPrincipal Jwt jwt) {
        
        String adminUserId = authService.getCurrentUserId(jwt);
        accessRequestService.denyRequest(requestId, adminUserId, denial);
        return ResponseEntity.ok().build();
    }
    
    // List pending requests (admins only)
    @GetMapping("/pending")
    public ResponseEntity<List<AccessRequestDTO>> getPendingRequests(@AuthenticationPrincipal Jwt jwt) {
        String userId = authService.getCurrentUserId(jwt);
        List<AccessRequest> requests = accessRequestService.getPendingRequestsForAdmin(userId);
        return ResponseEntity.ok(requests.stream().map(AccessRequestDTO::fromEntity).toList());
    }
}
```

### Testing

**Unit Tests (UserServiceTest.java):**
```java
@SpringBootTest
@Transactional
class UserServiceTest {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Test
    void testAutoRegistration() {
        // Given
        String azureObjectId = "test-azure-id-123";
        String email = "test@company.com";
        String displayName = "Test User";
        
        // When
        User user = userService.registerOrUpdateUser(azureObjectId, email, displayName);
        
        // Then
        assertNotNull(user.getUserId());
        assertEquals(email, user.getEmail());
        assertEquals(displayName, user.getDisplayName());
        assertFalse(user.getAdmin());
        assertFalse(user.getEnvAdmin());
        assertTrue(user.getIsActive());
        assertNotNull(user.getLastLoginAt());
    }
    
    @Test
    void testDuplicateLoginUpdatesTimestamp() {
        // Given
        String azureObjectId = "test-azure-id-456";
        User firstLogin = userService.registerOrUpdateUser(azureObjectId, "test2@company.com", "Test User 2");
        Timestamp firstLoginTime = firstLogin.getLastLoginAt();
        
        // Wait a bit
        Thread.sleep(1000);
        
        // When
        User secondLogin = userService.registerOrUpdateUser(azureObjectId, "test2@company.com", "Test User 2");
        
        // Then
        assertEquals(firstLogin.getUserId(), secondLogin.getUserId());
        assertTrue(secondLogin.getLastLoginAt().after(firstLoginTime));
    }
    
    @Test
    void testPromoteToEnvAdmin() {
        // Given
        User user = createTestUser();
        String adminId = createAdminUser().getUserId();
        
        // When
        userService.promoteToEnvAdmin(user.getUserId(), adminId);
        
        // Then
        User updated = userRepository.findById(user.getUserId()).orElseThrow();
        assertTrue(updated.getEnvAdmin());
    }
}
```

### Deliverables
- ✅ Azure AD SSO working
- ✅ User auto-registration on first login
- ✅ User management APIs (CRUD, promote)
- ✅ Access request workflow (create, approve, deny)
- ✅ Unit tests (>80% coverage)

### Definition of Done
- [ ] Users can log in via Azure AD
- [ ] New users auto-registered in database
- [ ] Admins can promote users to env_admin or admin
- [ ] Access request workflow functional end-to-end
- [ ] All tests passing

---

## Phase 2: Environment & VM Hierarchy (Weeks 4-5)

### Objectives
- Build Environment, Group, VM entities
- Implement CRUD operations
- Set up relationships and constraints

### Tasks

#### 3.1 Environment Management

**Environment Entity:**
```java
@Entity
@Table(name = "environment")
@Data
public class Environment {
    @Id
    @Column(name = "environment_id", length = 36)
    private String environmentId;
    
    @Column(unique = true, nullable = false)
    private String name;
    
    @Column(name = "display_name", nullable = false)
    private String displayName;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;
    
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;
    
    @Column(columnDefinition = "TEXT")
    private String metadata; // JSON
    
    @OneToMany(mappedBy = "environment", cascade = CascadeType.ALL)
    private List<VmGroup> groups;
}
```

**Environment Controller:**
```java
@RestController
@RequestMapping("/api/environments")
public class EnvironmentController {
    
    @Autowired
    private EnvironmentService environmentService;
    
    @Autowired
    private AuthorizationService authService;
    
    // List environments accessible to current user
    @GetMapping
    public ResponseEntity<List<EnvironmentDTO>> listEnvironments(@AuthenticationPrincipal Jwt jwt) {
        String userId = authService.getCurrentUserId(jwt);
        List<Environment> environments = environmentService.getAccessibleEnvironments(userId);
        return ResponseEntity.ok(environments.stream().map(EnvironmentDTO::fromEntity).toList());
    }
    
    // Get environment details
    @GetMapping("/{environmentId}")
    public ResponseEntity<EnvironmentDetailDTO> getEnvironment(
            @PathVariable String environmentId,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        authService.checkEnvironmentAccess(userId, environmentId, AccessLevel.VIEWER);
        
        Environment environment = environmentService.getEnvironmentWithDetails(environmentId);
        return ResponseEntity.ok(EnvironmentDetailDTO.fromEntity(environment));
    }
    
    // Create environment (admin only)
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EnvironmentDTO> createEnvironment(
            @RequestBody CreateEnvironmentDTO dto,
            @AuthenticationPrincipal Jwt jwt) {
        
        Environment created = environmentService.createEnvironment(dto);
        return ResponseEntity.status(HttpStatus.CREATED).ok(EnvironmentDTO.fromEntity(created));
    }
    
    // Update environment (admin or env-admin)
    @PutMapping("/{environmentId}")
    public ResponseEntity<EnvironmentDTO> updateEnvironment(
            @PathVariable String environmentId,
            @RequestBody UpdateEnvironmentDTO dto,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        authService.checkEnvironmentAdminAccess(userId, environmentId);
        
        Environment updated = environmentService.updateEnvironment(environmentId, dto);
        return ResponseEntity.ok(EnvironmentDTO.fromEntity(updated));
    }
}
```

#### 3.2 Group Management

**Group Entity:**
```java
@Entity
@Table(name = "vm_group")
@Data
public class VmGroup {
    @Id
    @Column(name = "group_id", length = 36)
    private String groupId;
    
    @ManyToOne
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;
    
    @Column(nullable = false)
    private String name;
    
    @Column(name = "display_name")
    private String displayName;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "sequence_position", nullable = false)
    private Integer sequencePosition;
    
    @Column(name = "depends_on_group_ids", columnDefinition = "TEXT")
    private String dependsOnGroupIds; // JSON array: ["group-id-1", "group-id-2"]
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;
    
    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL)
    private List<Vm> vms;
    
    // Helper method to get dependencies as list
    @Transient
    public List<String> getDependencies() {
        if (dependsOnGroupIds == null || dependsOnGroupIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return new ObjectMapper().readValue(dependsOnGroupIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
```

**Group Service with Validation:**
```java
@Service
@Slf4j
public class GroupService {
    
    @Autowired
    private GroupRepository groupRepository;
    
    @Autowired
    private DependencyValidator dependencyValidator;
    
    @Transactional
    public VmGroup createGroup(String environmentId, CreateGroupDTO dto) {
        // Validate sequence position is unique within environment
        boolean sequenceExists = groupRepository.existsByEnvironmentIdAndSequencePosition(
            environmentId, dto.getSequencePosition());
        if (sequenceExists) {
            throw new ValidationException("Sequence position " + dto.getSequencePosition() + " already exists");
        }
        
        // Validate dependencies exist and don't create circular reference
        if (dto.getDependsOnGroupIds() != null && !dto.getDependsOnGroupIds().isEmpty()) {
            dependencyValidator.validateGroupDependencies(environmentId, dto.getDependsOnGroupIds());
        }
        
        VmGroup group = new VmGroup();
        group.setGroupId(UUID.randomUUID().toString());
        group.setEnvironment(new Environment(environmentId));
        group.setName(dto.getName());
        group.setDisplayName(dto.getDisplayName());
        group.setDescription(dto.getDescription());
        group.setSequencePosition(dto.getSequencePosition());
        
        // Convert dependencies to JSON
        if (dto.getDependsOnGroupIds() != null) {
            try {
                group.setDependsOnGroupIds(new ObjectMapper().writeValueAsString(dto.getDependsOnGroupIds()));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize dependencies", e);
            }
        }
        
        return groupRepository.save(group);
    }
    
    public List<VmGroup> getGroupsInStartOrder(String environmentId) {
        // Get all groups for environment
        List<VmGroup> allGroups = groupRepository.findByEnvironmentIdOrderBySequencePosition(environmentId);
        
        // Sort by dependency order (topological sort)
        return dependencyValidator.topologicalSort(allGroups);
    }
}
```

#### 3.3 VM Management

**VM Entity:**
```java
@Entity
@Table(name = "vm")
@Data
public class Vm {
    @Id
    @Column(name = "vm_id", length = 36)
    private String vmId;
    
    @ManyToOne
    @JoinColumn(name = "group_id", nullable = false)
    private VmGroup group;
    
    @Column(nullable = false)
    private String name;
    
    @Column(name = "display_name")
    private String displayName;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CloudProvider provider; // AWS, AZURE, GCP
    
    @Column(nullable = false)
    private String region;
    
    @Column(name = "provider_vm_id", nullable = false)
    private String providerVmId; // i-xxxxx for AWS, /subscriptions/... for Azure
    
    @Enumerated(EnumType.STRING)
    @Column(name = "vm_type", length = 20, nullable = false)
    private VmType vmType; // DEV (only type for now)
    
    @Column(name = "sequence_position", nullable = false)
    private Integer sequencePosition;
    
    @Column(name = "depends_on_vm_ids", columnDefinition = "TEXT")
    private String dependsOnVmIds; // JSON array of VM IDs within same group
    
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private VmStatus status; // RUNNING, STOPPED, STARTING, STOPPING, ERROR, UNKNOWN
    
    @Column(name = "last_status_update")
    private Timestamp lastStatusUpdate;
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;
    
    @Column(columnDefinition = "TEXT")
    private String tags; // JSON
    
    // Helper methods
    @Transient
    public List<String> getDependencies() {
        if (dependsOnVmIds == null || dependsOnVmIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return new ObjectMapper().readValue(dependsOnVmIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
```

**VM Controller:**
```java
@RestController
@RequestMapping("/api/environments/{environmentId}/vms")
public class VmController {
    
    @Autowired
    private VmService vmService;
    
    @Autowired
    private AuthorizationService authService;
    
    // List VMs in environment (grouped by group)
    @GetMapping
    public ResponseEntity<List<VmGroupDTO>> listVms(
            @PathVariable String environmentId,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        authService.checkEnvironmentAccess(userId, environmentId, AccessLevel.VIEWER);
        
        List<VmGroupDTO> groups = vmService.getVmsGroupedByGroup(environmentId);
        return ResponseEntity.ok(groups);
    }
    
    // Get VM details
    @GetMapping("/{vmId}")
    public ResponseEntity<VmDetailDTO> getVm(
            @PathVariable String environmentId,
            @PathVariable String vmId,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        authService.checkEnvironmentAccess(userId, environmentId, AccessLevel.VIEWER);
        
        Vm vm = vmService.getVmDetail(vmId);
        return ResponseEntity.ok(VmDetailDTO.fromEntity(vm));
    }
    
    // Register VM (admin/env-admin only)
    @PostMapping
    public ResponseEntity<VmDTO> registerVm(
            @PathVariable String environmentId,
            @RequestBody RegisterVmDTO dto,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        authService.checkEnvironmentAdminAccess(userId, environmentId);
        
        Vm vm = vmService.registerVm(dto);
        return ResponseEntity.status(HttpStatus.CREATED).ok(VmDTO.fromEntity(vm));
    }
}
```

### Testing

**Group Dependency Tests:**
```java
@SpringBootTest
@Transactional
class GroupDependencyTest {
    
    @Autowired
    private GroupService groupService;
    
    @Autowired
    private DependencyValidator dependencyValidator;
    
    @Test
    void testSimpleChainDependency() {
        // Group1 -> Group2 -> Group3
        String envId = createTestEnvironment();
        
        VmGroup group1 = createGroup(envId, "data-tier", 1, null);
        VmGroup group2 = createGroup(envId, "backend-tier", 2, List.of(group1.getGroupId()));
        VmGroup group3 = createGroup(envId, "frontend-tier", 3, List.of(group2.getGroupId()));
        
        // Verify topological sort
        List<VmGroup> sorted = groupService.getGroupsInStartOrder(envId);
        assertEquals(group1.getGroupId(), sorted.get(0).getGroupId());
        assertEquals(group2.getGroupId(), sorted.get(1).getGroupId());
        assertEquals(group3.getGroupId(), sorted.get(2).getGroupId());
    }
    
    @Test
    void testParallelDependencies() {
        // Group1 <- Group2
        //        <- Group3
        String envId = createTestEnvironment();
        
        VmGroup group1 = createGroup(envId, "data-tier", 1, null);
        VmGroup group2 = createGroup(envId, "backend-tier", 2, List.of(group1.getGroupId()));
        VmGroup group3 = createGroup(envId, "cache-tier", 2, List.of(group1.getGroupId())); // Same sequence
        
        List<VmGroup> sorted = groupService.getGroupsInStartOrder(envId);
        
        // Group1 must be first
        assertEquals(group1.getGroupId(), sorted.get(0).getGroupId());
        
        // Group2 and Group3 can be in any order (parallel)
        assertTrue(sorted.get(1).getGroupId().equals(group2.getGroupId()) || 
                   sorted.get(1).getGroupId().equals(group3.getGroupId()));
    }
    
    @Test
    void testCircularDependencyDetection() {
        String envId = createTestEnvironment();
        
        VmGroup group1 = createGroup(envId, "group1", 1, null);
        VmGroup group2 = createGroup(envId, "group2", 2, List.of(group1.getGroupId()));
        
        // Try to create Group3 that depends on Group2, then update Group1 to depend on Group3
        VmGroup group3 = createGroup(envId, "group3", 3, List.of(group2.getGroupId()));
        
        // Now try to make Group1 depend on Group3 (creates cycle: 1->3->2->1)
        assertThrows(CircularDependencyException.class, () -> {
            groupService.updateGroupDependencies(group1.getGroupId(), List.of(group3.getGroupId()));
        });
    }
}
```

### Deliverables
- ✅ Environment CRUD operations
- ✅ Group CRUD with dependency validation
- ✅ VM registration and management
- ✅ Relationship constraints enforced
- ✅ Unit tests for hierarchy operations

### Definition of Done
- [ ] Environments can be created and listed
- [ ] Groups can be created with dependencies
- [ ] VMs can be registered to groups
- [ ] Circular dependency detection works
- [ ] Foreign key constraints prevent orphaned data

---

## Phase 3: Dependency Engine (CRITICAL) (Weeks 6-7)

### Objectives
- Build robust dependency validation engine
- Implement topological sorting for group/VM ordering
- Handle circular dependency detection
- Support parallel execution planning

### Tasks

#### 4.1 Dependency Validator

**DependencyValidator.java:**
```java
@Component
@Slf4j
public class DependencyValidator {
    
    @Autowired
    private GroupRepository groupRepository;
    
    @Autowired
    private VmRepository vmRepository;
    
    /**
     * Validates that group dependencies:
     * 1. All referenced groups exist
     * 2. No circular dependencies
     * 3. All dependencies are in same environment
     */
    public void validateGroupDependencies(String environmentId, List<String> dependsOnGroupIds) {
        if (dependsOnGroupIds == null || dependsOnGroupIds.isEmpty()) {
            return;
        }
        
        // Check all dependencies exist and are in same environment
        for (String depGroupId : dependsOnGroupIds) {
            VmGroup depGroup = groupRepository.findById(depGroupId)
                .orElseThrow(() -> new ValidationException("Dependency group not found: " + depGroupId));
            
            if (!depGroup.getEnvironment().getEnvironmentId().equals(environmentId)) {
                throw new ValidationException("Dependency group must be in same environment");
            }
        }
    }
    
    /**
     * Detect circular dependencies in group graph
     */
    public void detectCircularDependency(String environmentId, String newGroupId, List<String> dependsOn) {
        // Build dependency graph
        Map<String, List<String>> graph = buildGroupDependencyGraph(environmentId);
        
        // Add new dependency edges
        graph.put(newGroupId, dependsOn);
        
        // Run cycle detection (DFS-based)
        if (hasCycle(graph)) {
            String cyclePath = findCyclePath(graph, newGroupId);
            throw new CircularDependencyException("Circular dependency detected: " + cyclePath);
        }
    }
    
    /**
     * Topological sort for determining start order
     */
    public List<VmGroup> topologicalSort(List<VmGroup> groups) {
        // Build adjacency list
        Map<String, List<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, VmGroup> groupMap = new HashMap<>();
        
        for (VmGroup group : groups) {
            groupMap.put(group.getGroupId(), group);
            graph.putIfAbsent(group.getGroupId(), new ArrayList<>());
            inDegree.putIfAbsent(group.getGroupId(), 0);
            
            for (String depId : group.getDependencies()) {
                graph.putIfAbsent(depId, new ArrayList<>());
                graph.get(depId).add(group.getGroupId());
                inDegree.put(group.getGroupId(), inDegree.get(group.getGroupId()) + 1);
            }
        }
        
        // Kahn's algorithm for topological sort
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }
        
        List<VmGroup> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String groupId = queue.poll();
            sorted.add(groupMap.get(groupId));
            
            for (String neighbor : graph.get(groupId)) {
                inDegree.put(neighbor, inDegree.get(neighbor) - 1);
                if (inDegree.get(neighbor) == 0) {
                    queue.offer(neighbor);
                }
            }
        }
        
        if (sorted.size() != groups.size()) {
            throw new CircularDependencyException("Circular dependency detected in group graph");
        }
        
        return sorted;
    }
    
    /**
     * Check if VM dependencies are satisfied (all deps in RUNNING state)
     */
    public boolean areVmDependenciesSatisfied(Vm vm) {
        if (vm.getDependencies().isEmpty()) {
            return true;
        }
        
        List<Vm> dependentVms = vmRepository.findAllById(vm.getDependencies());
        
        // All dependencies must be in RUNNING state
        return dependentVms.stream().allMatch(dep -> dep.getStatus() == VmStatus.RUNNING);
    }
    
    /**
     * Check if all VMs in dependency groups are running
     */
    public boolean areGroupDependenciesSatisfied(VmGroup group) {
        if (group.getDependencies().isEmpty()) {
            return true;
        }
        
        for (String depGroupId : group.getDependencies()) {
            List<Vm> vmsInDepGroup = vmRepository.findByGroupId(depGroupId);
            
            // ALL VMs in dependent group must be RUNNING
            boolean allRunning = vmsInDepGroup.stream()
                .allMatch(vm -> vm.getStatus() == VmStatus.RUNNING);
            
            if (!allRunning) {
                log.debug("Group {} dependency not satisfied: group {} has non-running VMs", 
                    group.getGroupId(), depGroupId);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Get VMs that can start in parallel (same sequence, all deps met)
     */
    public List<List<Vm>> getVmStartBatches(String groupId) {
        List<Vm> allVms = vmRepository.findByGroupIdOrderBySequencePosition(groupId);
        
        // Group by sequence position
        Map<Integer, List<Vm>> bySequence = allVms.stream()
            .collect(Collectors.groupingBy(Vm::getSequencePosition));
        
        // Sort by sequence and return as list of batches
        return bySequence.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());
    }
    
    // ============= Private Helper Methods =============
    
    private Map<String, List<String>> buildGroupDependencyGraph(String environmentId) {
        List<VmGroup> groups = groupRepository.findByEnvironmentId(environmentId);
        Map<String, List<String>> graph = new HashMap<>();
        
        for (VmGroup group : groups) {
            graph.put(group.getGroupId(), group.getDependencies());
        }
        
        return graph;
    }
    
    private boolean hasCycle(Map<String, List<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String node : graph.keySet()) {
            if (hasCycleDFS(node, graph, visited, recursionStack)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean hasCycleDFS(String node, Map<String, List<String>> graph, 
                                Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(node)) {
            return true; // Cycle detected
        }
        
        if (visited.contains(node)) {
            return false; // Already processed
        }
        
        visited.add(node);
        recursionStack.add(node);
        
        List<String> neighbors = graph.getOrDefault(node, Collections.emptyList());
        for (String neighbor : neighbors) {
            if (hasCycleDFS(neighbor, graph, visited, recursionStack)) {
                return true;
            }
        }
        
        recursionStack.remove(node);
        return false;
    }
    
    private String findCyclePath(Map<String, List<String>> graph, String startNode) {
        // DFS to find cycle path for error message
        Set<String> visited = new HashSet<>();
        List<String> path = new ArrayList<>();
        
        if (findCyclePathDFS(startNode, graph, visited, path)) {
            return String.join(" -> ", path);
        }
        
        return "Unknown cycle";
    }
    
    private boolean findCyclePathDFS(String node, Map<String, List<String>> graph,
                                      Set<String> visited, List<String> path) {
        if (path.contains(node)) {
            // Found cycle - add current node to complete the cycle
            path.add(node);
            
            // Trim path to just the cycle portion
            int cycleStart = path.indexOf(node);
            List<String> cyclePortion = path.subList(cycleStart, path.size());
            path.clear();
            path.addAll(cyclePortion);
            
            return true;
        }
        
        if (visited.contains(node)) {
            return false;
        }
        
        visited.add(node);
        path.add(node);
        
        List<String> neighbors = graph.getOrDefault(node, Collections.emptyList());
        for (String neighbor : neighbors) {
            if (findCyclePathDFS(neighbor, graph, visited, path)) {
                return true;
            }
        }
        
        path.remove(path.size() - 1);
        return false;
    }
}
```

#### 4.2 VM Dependency Validator

**VM-specific dependency validation:**
```java
@Component
public class VmDependencyValidator {
    
    @Autowired
    private VmRepository vmRepository;
    
    /**
     * Validate VM dependencies:
     * 1. All dependencies exist
     * 2. All dependencies are in SAME group
     * 3. No circular dependencies
     */
    public void validateVmDependencies(String groupId, String vmId, List<String> dependsOnVmIds) {
        if (dependsOnVmIds == null || dependsOnVmIds.isEmpty()) {
            return;
        }
        
        for (String depVmId : dependsOnVmIds) {
            Vm depVm = vmRepository.findById(depVmId)
                .orElseThrow(() -> new ValidationException("Dependency VM not found: " + depVmId));
            
            // Verify dependency is in same group
            if (!depVm.getGroup().getGroupId().equals(groupId)) {
                throw new ValidationException("VM dependencies must be within the same group");
            }
        }
        
        // Check for circular dependencies
        detectVmCircularDependency(groupId, vmId, dependsOnVmIds);
    }
    
    private void detectVmCircularDependency(String groupId, String newVmId, List<String> dependsOn) {
        List<Vm> vmsInGroup = vmRepository.findByGroupId(groupId);
        
        Map<String, List<String>> graph = new HashMap<>();
        for (Vm vm : vmsInGroup) {
            graph.put(vm.getVmId(), vm.getDependencies());
        }
        
        graph.put(newVmId, dependsOn);
        
        // Use same cycle detection as groups
        if (hasCycle(graph)) {
            throw new CircularDependencyException("Circular VM dependency detected in group " + groupId);
        }
    }
    
    private boolean hasCycle(Map<String, List<String>> graph) {
        // Same implementation as DependencyValidator.hasCycle()
        // ... (omitted for brevity)
    }
}
```

### Testing

**Comprehensive Dependency Tests:**
```java
@SpringBootTest
@Transactional
class DependencyEngineTest {
    
    @Autowired
    private DependencyValidator dependencyValidator;
    
    @Autowired
    private VmDependencyValidator vmDependencyValidator;
    
    @Test
    void testComplexDependencyChain() {
        /*
         * Setup:
         * Group1 (data-tier) -> no deps
         * Group2 (backend) -> depends on Group1
         * Group3 (cache) -> depends on Group1
         * Group4 (frontend) -> depends on Group2, Group3
         */
        String envId = createTestEnvironment();
        
        VmGroup group1 = createGroup(envId, "data-tier", 1, null);
        VmGroup group2 = createGroup(envId, "backend", 2, List.of(group1.getGroupId()));
        VmGroup group3 = createGroup(envId, "cache", 2, List.of(group1.getGroupId()));
        VmGroup group4 = createGroup(envId, "frontend", 3, List.of(group2.getGroupId(), group3.getGroupId()));
        
        List<VmGroup> sorted = dependencyValidator.topologicalSort(
            List.of(group1, group2, group3, group4));
        
        // Verify order
        assertEquals(group1.getGroupId(), sorted.get(0).getGroupId());
        assertTrue(sorted.get(1).getGroupId().equals(group2.getGroupId()) || 
                   sorted.get(1).getGroupId().equals(group3.getGroupId()));
        assertEquals(group4.getGroupId(), sorted.get(3).getGroupId());
    }
    
    @Test
    void testVmDependencyWithinGroup() {
        VmGroup group = createTestGroup();
        
        Vm vm1 = createVm(group, "db-primary", 1, null);
        Vm vm2 = createVm(group, "db-replica", 2, List.of(vm1.getVmId()));
        Vm vm3 = createVm(group, "db-backup", 3, List.of(vm2.getVmId()));
        
        // Verify dependencies
        assertTrue(dependencyValidator.areVmDependenciesSatisfied(vm1));
        
        // VM2 depends on VM1 - not satisfied yet
        assertFalse(dependencyValidator.areVmDependenciesSatisfied(vm2));
        
        // Start VM1
        vm1.setStatus(VmStatus.RUNNING);
        vmRepository.save(vm1);
        
        // Now VM2 deps are satisfied
        assertTrue(dependencyValidator.areVmDependenciesSatisfied(vm2));
    }
    
    @Test
    void testCircularDependencyRejection() {
        String envId = createTestEnvironment();
        
        VmGroup group1 = createGroup(envId, "group1", 1, null);
        VmGroup group2 = createGroup(envId, "group2", 2, List.of(group1.getGroupId()));
        VmGroup group3 = createGroup(envId, "group3", 3, List.of(group2.getGroupId()));
        
        // Try to make group1 depend on group3 (creates cycle)
        assertThrows(CircularDependencyException.class, () -> {
            dependencyValidator.detectCircularDependency(
                envId, 
                group1.getGroupId(), 
                List.of(group3.getGroupId())
            );
        });
    }
    
    @Test
    void testCrossGroupVmDependencyRejection() {
        VmGroup group1 = createTestGroup("group1");
        VmGroup group2 = createTestGroup("group2");
        
        Vm vmInGroup1 = createVm(group1, "vm1", 1, null);
        
        // Try to create VM in group2 that depends on VM in group1 (should fail)
        assertThrows(ValidationException.class, () -> {
            vmDependencyValidator.validateVmDependencies(
                group2.getGroupId(), 
                "new-vm-id", 
                List.of(vmInGroup1.getVmId())
            );
        });
    }
}
```

### Deliverables
- ✅ Dependency validation engine
- ✅ Topological sort for groups and VMs
- ✅ Circular dependency detection
- ✅ Parallel execution planning
- ✅ Comprehensive unit tests (>90% coverage)

### Definition of Done
- [ ] All dependency scenarios tested
- [ ] Circular dependencies detected and rejected
- [ ] Topological sort produces correct ordering
- [ ] Parallel execution groups identified correctly
- [ ] No bugs in dependency logic

---

## Phase 4: Lock Management (Week 8)

### Objectives
- Implement environment-wide locking
- Handle concurrent lock attempts
- Admin lock breaking functionality

### Tasks

#### 5.1 Environment Lock Entity

**EnvironmentLock.java:**
```java
@Entity
@Table(name = "environment_lock")
@Data
public class EnvironmentLock {
    @Id
    @Column(name = "lock_id", length = 36)
    private String lockId;
    
    @ManyToOne
    @JoinColumn(name = "environment_id", nullable = false, unique = true)
    private Environment environment;
    
    @ManyToOne
    @JoinColumn(name = "locked_by_user_id", nullable = false)
    private User lockedBy;
    
    @Column(name = "acquired_at", nullable = false)
    @CreationTimestamp
    private Timestamp acquiredAt;
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    @Column(name = "released_at")
    private Timestamp releasedAt;
    
    @ManyToOne
    @JoinColumn(name = "released_by_user_id")
    private User releasedBy;
    
    @Column(name = "lock_reason", columnDefinition = "TEXT")
    private String lockReason;
    
    @Column(name = "was_broken", nullable = false)
    private Boolean wasBroken = false;
    
    @ManyToOne
    @JoinColumn(name = "broken_by_user_id")
    private User brokenBy;
    
    @Column(name = "break_reason", columnDefinition = "TEXT")
    private String breakReason;
}
```

#### 5.2 Lock Service

**LockService.java:**
```java
@Service
@Slf4j
public class LockService {
    
    @Autowired
    private EnvironmentLockRepository lockRepository;
    
    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private AuditService auditService;
    
    /**
     * Acquire lock on environment
     * Uses database row-level locking to prevent concurrent acquisition
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public EnvironmentLock acquireLock(String environmentId, String userId, String reason) {
        // Check if environment is already locked
        Optional<EnvironmentLock> existingLock = lockRepository
            .findByEnvironmentIdAndIsActiveTrue(environmentId);
        
        if (existingLock.isPresent()) {
            EnvironmentLock activeLock = existingLock.get();
            
            // If same user holds the lock, just return it
            if (activeLock.getLockedBy().getUserId().equals(userId)) {
                log.info("User {} already holds lock on environment {}", userId, environmentId);
                return activeLock;
            }
            
            // Lock held by another user
            throw new LockAlreadyHeldException(
                "Environment locked by " + activeLock.getLockedBy().getEmail() + 
                " since " + activeLock.getAcquiredAt()
            );
        }
        
        // Acquire new lock
        EnvironmentLock lock = new EnvironmentLock();
        lock.setLockId(UUID.randomUUID().toString());
        lock.setEnvironment(new Environment(environmentId));
        lock.setLockedBy(new User(userId));
        lock.setLockReason(reason);
        lock.setIsActive(true);
        
        lock = lockRepository.save(lock);
        
        log.info("Lock acquired on environment {} by user {}", environmentId, userId);
        
        // Audit log
        auditService.logAction(userId, environmentId, AuditAction.LOCK_ACQUIRED, 
            "Reason: " + reason);
        
        return lock;
    }
    
    /**
     * Release lock
     */
    @Transactional
    public void releaseLock(String environmentId, String userId) {
        EnvironmentLock lock = lockRepository.findByEnvironmentIdAndIsActiveTrue(environmentId)
            .orElseThrow(() -> new NoActiveLockException("No active lock on environment"));
        
        // Verify user holds the lock
        if (!lock.getLockedBy().getUserId().equals(userId)) {
            throw new UnauthorizedException("You do not hold the lock on this environment");
        }
        
        lock.setIsActive(false);
        lock.setReleasedAt(Timestamp.from(Instant.now()));
        lock.setReleasedBy(new User(userId));
        
        lockRepository.save(lock);
        
        log.info("Lock released on environment {} by user {}", environmentId, userId);
        
        auditService.logAction(userId, environmentId, AuditAction.LOCK_RELEASED, null);
    }
    
    /**
     * Admin/Env-Admin breaks lock (emergency)
     */
    @Transactional
    public void breakLock(String environmentId, String adminUserId, String breakReason) {
        EnvironmentLock lock = lockRepository.findByEnvironmentIdAndIsActiveTrue(environmentId)
            .orElseThrow(() -> new NoActiveLockException("No active lock to break"));
        
        String originalLockHolder = lock.getLockedBy().getUserId();
        
        lock.setIsActive(false);
        lock.setReleasedAt(Timestamp.from(Instant.now()));
        lock.setWasBroken(true);
        lock.setBrokenBy(new User(adminUserId));
        lock.setBreakReason(breakReason);
        
        lockRepository.save(lock);
        
        log.warn("Lock on environment {} broken by admin {} (was held by {}). Reason: {}", 
            environmentId, adminUserId, originalLockHolder, breakReason);
        
        // Notify original lock holder
        notificationService.sendNotification(
            originalLockHolder,
            "Your lock on environment " + environmentId + " was broken by an administrator",
            NotificationType.LOCK_BROKEN
        );
        
        // Audit log
        auditService.logAction(adminUserId, environmentId, AuditAction.LOCK_BROKEN, 
            "Original holder: " + originalLockHolder + ". Reason: " + breakReason);
    }
    
    /**
     * Check if environment is locked
     */
    public boolean isEnvironmentLocked(String environmentId) {
        return lockRepository.findByEnvironmentIdAndIsActiveTrue(environmentId).isPresent();
    }
    
    /**
     * Get current lock holder
     */
    public Optional<EnvironmentLock> getCurrentLock(String environmentId) {
        return lockRepository.findByEnvironmentIdAndIsActiveTrue(environmentId);
    }
    
    /**
     * Verify user can perform operation (has lock or no lock exists)
     */
    public void verifyLockPermission(String environmentId, String userId, OperationType operation) {
        Optional<EnvironmentLock> lock = lockRepository.findByEnvironmentIdAndIsActiveTrue(environmentId);
        
        if (lock.isEmpty()) {
            // No lock - OK to proceed (lock will be acquired)
            return;
        }
        
        // Lock exists - verify user holds it
        if (!lock.get().getLockedBy().getUserId().equals(userId)) {
            throw new LockAlreadyHeldException(
                "Environment is locked by " + lock.get().getLockedBy().getEmail()
            );
        }
    }
}
```

#### 5.3 Lock Controller

**LockController.java:**
```java
@RestController
@RequestMapping("/api/environments/{environmentId}/lock")
public class LockController {
    
    @Autowired
    private LockService lockService;
    
    @Autowired
    private AuthorizationService authService;
    
    // Get current lock status
    @GetMapping
    public ResponseEntity<LockStatusDTO> getLockStatus(@PathVariable String environmentId) {
        Optional<EnvironmentLock> lock = lockService.getCurrentLock(environmentId);
        
        if (lock.isPresent()) {
            return ResponseEntity.ok(LockStatusDTO.fromEntity(lock.get()));
        } else {
            return ResponseEntity.ok(LockStatusDTO.noLock());
        }
    }
    
    // Acquire lock
    @PostMapping("/acquire")
    public ResponseEntity<LockStatusDTO> acquireLock(
            @PathVariable String environmentId,
            @RequestBody AcquireLockDTO dto,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        authService.checkEnvironmentAccess(userId, environmentId, AccessLevel.USER);
        
        EnvironmentLock lock = lockService.acquireLock(environmentId, userId, dto.getReason());
        return ResponseEntity.ok(LockStatusDTO.fromEntity(lock));
    }
    
    // Release lock
    @PostMapping("/release")
    public ResponseEntity<Void> releaseLock(
            @PathVariable String environmentId,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        lockService.releaseLock(environmentId, userId);
        
        return ResponseEntity.ok().build();
    }
    
    // Break lock (admin/env-admin only)
    @PostMapping("/break")
    public ResponseEntity<Void> breakLock(
            @PathVariable String environmentId,
            @RequestBody BreakLockDTO dto,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = authService.getCurrentUserId(jwt);
        authService.checkEnvironmentAdminAccess(userId, environmentId);
        
        lockService.breakLock(environmentId, userId, dto.getReason());
        
        return ResponseEntity.ok().build();
    }
}
```

### Testing

**Lock Concurrency Tests:**
```java
@SpringBootTest
@Transactional
class LockConcurrencyTest {
    
    @Autowired
    private LockService lockService;
    
    @Test
    void testConcurrentLockAttempts() throws Exception {
        String envId = "test-env-1";
        String user1 = "user-1";
        String user2 = "user-2";
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        // Two users try to acquire lock simultaneously
        executor.submit(() -> {
            try {
                lockService.acquireLock(envId, user1, "Test reason 1");
                successCount.incrementAndGet();
            } catch (LockAlreadyHeldException e) {
                failureCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
        
        executor.submit(() -> {
            try {
                lockService.acquireLock(envId, user2, "Test reason 2");
                successCount.incrementAndGet();
            } catch (LockAlreadyHeldException e) {
                failureCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
        
        latch.await(5, TimeUnit.SECONDS);
        
        // Only one should succeed
        assertEquals(1, successCount.get());
        assertEquals(1, failureCount.get());
    }
    
    @Test
    void testLockBreakingNotifiesOriginalHolder() {
        String envId = "test-env-2";
        String userId = "user-1";
        String adminId = "admin-1";
        
        // User acquires lock
        lockService.acquireLock(envId, userId, "Working on env");
        
        // Admin breaks lock
        lockService.breakLock(envId, adminId, "Emergency maintenance");
        
        // Verify lock is broken
        assertFalse(lockService.isEnvironmentLocked(envId));
        
        // Verify notification sent to user
        verify(notificationService).sendNotification(
            eq(userId),
            contains("lock"),
            eq(NotificationType.LOCK_BROKEN)
        );
    }
}
```

### Deliverables
- ✅ Environment lock entity and repository
- ✅ Lock acquisition with row-level locking
- ✅ Lock release functionality
- ✅ Admin lock breaking
- ✅ Concurrency tests

### Definition of Done
- [ ] Concurrent lock attempts handled correctly
- [ ] Lock breaking works and notifies original holder
- [ ] Audit logs capture all lock operations
- [ ] Tests verify thread-safety

---

## Phase 5: Cloud Provider Integration (Weeks 9-10)

### Objectives
- Abstract cloud provider interfaces
- Implement AWS EC2 operations
- Implement Azure VM operations
- Implement GCP Compute Engine operations
- Error handling and retry logic

### Tasks

#### 6.1 Cloud Provider Abstraction

**CloudProvider Interface:**
```java
public interface CloudProviderService {
    
    /**
     * Start a VM
     * @return Operation result with status
     */
    VmOperationResult startVm(String providerVmId, String region);
    
    /**
     * Stop a VM
     */
    VmOperationResult stopVm(String providerVmId, String region);
    
    /**
     * Get current VM status
     */
    VmStatus getVmStatus(String providerVmId, String region);
    
    /**
     * Get VM logs (last N lines)
     */
    List<String> getVmLogs(String providerVmId, String region, int lines);
    
    /**
     * Verify VM exists
     */
    boolean vmExists(String providerVmId, String region);
    
    /**
     * Get VM details
     */
    VmProviderDetails getVmDetails(String providerVmId, String region);
}
```

**VmOperationResult DTO:**
```java
@Data
@Builder
public class VmOperationResult {
    private boolean success;
    private VmStatus resultingStatus;
    private String errorMessage;
    private String errorCode;
    private Timestamp timestamp;
    
    public static VmOperationResult success(VmStatus status) {
        return VmOperationResult.builder()
            .success(true)
            .resultingStatus(status)
            .timestamp(Timestamp.from(Instant.now()))
            .build();
    }
    
    public static VmOperationResult failure(String errorMessage, String errorCode) {
        return VmOperationResult.builder()
            .success(false)
            .errorMessage(errorMessage)
            .errorCode(errorCode)
            .timestamp(Timestamp.from(Instant.now()))
            .build();
    }
}
```

#### 6.2 AWS EC2 Implementation

**AwsEc2Service.java:**
```java
@Service
@Slf4j
public class AwsEc2Service implements CloudProviderService {
    
    private final AmazonEC2 ec2Client;
    
    @Autowired
    public AwsEc2Service(AwsCredentialsService credentialsService) {
        AWSCredentials credentials = credentialsService.getAwsCredentials();
        this.ec2Client = AmazonEC2ClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(credentials))
            .withRegion(Regions.US_EAST_1) // TODO: Make region configurable
            .build();
    }
    
    @Override
    @Retryable(
        value = { AmazonEC2Exception.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public VmOperationResult startVm(String instanceId, String region) {
        try {
            log.info("Starting AWS EC2 instance: {}", instanceId);
            
            StartInstancesRequest request = new StartInstancesRequest()
                .withInstanceIds(instanceId);
            
            StartInstancesResult result = ec2Client.startInstances(request);
            
            // Wait for instance to be in running state (with timeout)
            waitForInstanceState(instanceId, InstanceStateName.Running, 120);
            
            log.info("Successfully started instance: {}", instanceId);
            return VmOperationResult.success(VmStatus.RUNNING);
            
        } catch (AmazonEC2Exception e) {
            log.error("Failed to start instance {}: {}", instanceId, e.getMessage());
            return VmOperationResult.failure(e.getMessage(), e.getErrorCode());
        } catch (Exception e) {
            log.error("Unexpected error starting instance {}: {}", instanceId, e.getMessage());
            return VmOperationResult.failure(e.getMessage(), "UNKNOWN_ERROR");
        }
    }
    
    @Override
    @Retryable(
        value = { AmazonEC2Exception.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public VmOperationResult stopVm(String instanceId, String region) {
        try {
            log.info("Stopping AWS EC2 instance: {}", instanceId);
            
            StopInstancesRequest request = new StopInstancesRequest()
                .withInstanceIds(instanceId);
            
            StopInstancesResult result = ec2Client.stopInstances(request);
            
            // Wait for instance to be stopped
            waitForInstanceState(instanceId, InstanceStateName.Stopped, 120);
            
            log.info("Successfully stopped instance: {}", instanceId);
            return VmOperationResult.success(VmStatus.STOPPED);
            
        } catch (AmazonEC2Exception e) {
            log.error("Failed to stop instance {}: {}", instanceId, e.getMessage());
            return VmOperationResult.failure(e.getMessage(), e.getErrorCode());
        }
    }
    
    @Override
    public VmStatus getVmStatus(String instanceId, String region) {
        try {
            DescribeInstancesRequest request = new DescribeInstancesRequest()
                .withInstanceIds(instanceId);
            
            DescribeInstancesResult result = ec2Client.describeInstances(request);
            
            if (result.getReservations().isEmpty() || 
                result.getReservations().get(0).getInstances().isEmpty()) {
                return VmStatus.UNKNOWN;
            }
            
            Instance instance = result.getReservations().get(0).getInstances().get(0);
            String state = instance.getState().getName();
            
            return mapAwsStateToVmStatus(state);
            
        } catch (AmazonEC2Exception e) {
            log.error("Failed to get instance status {}: {}", instanceId, e.getMessage());
            return VmStatus.ERROR;
        }
    }
    
    @Override
    public List<String> getVmLogs(String instanceId, String region, int lines) {
        try {
            GetConsoleOutputRequest request = new GetConsoleOutputRequest(instanceId);
            GetConsoleOutputResult result = ec2Client.getConsoleOutput(request);
            
            String output = result.getDecodedOutput();
            if (output == null || output.isEmpty()) {
                return Collections.emptyList();
            }
            
            // Get last N lines
            String[] allLines = output.split("\n");
            int start = Math.max(0, allLines.length - lines);
            return Arrays.asList(Arrays.copyOfRange(allLines, start, allLines.length));
            
        } catch (AmazonEC2Exception e) {
            log.error("Failed to get console output for {}: {}", instanceId, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    // Helper methods
    private void waitForInstanceState(String instanceId, InstanceStateName targetState, int timeoutSeconds) 
            throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeout = timeoutSeconds * 1000L;
        
        while (System.currentTimeMillis() - startTime < timeout) {
            VmStatus status = getVmStatus(instanceId, null);
            
            if (status == mapAwsStateToVmStatus(targetState.toString())) {
                return;
            }
            
            Thread.sleep(5000); // Check every 5 seconds
        }
        
        throw new TimeoutException("Instance " + instanceId + " did not reach state " + targetState);
    }
    
    private VmStatus mapAwsStateToVmStatus(String awsState) {
        return switch (awsState.toLowerCase()) {
            case "running" -> VmStatus.RUNNING;
            case "stopped" -> VmStatus.STOPPED;
            case "pending" -> VmStatus.STARTING;
            case "stopping" -> VmStatus.STOPPING;
            case "terminated", "shutting-down" -> VmStatus.ERROR;
            default -> VmStatus.UNKNOWN;
        };
    }
    
    @Override
    public boolean vmExists(String instanceId, String region) {
        try {
            getVmStatus(instanceId, region);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public VmProviderDetails getVmDetails(String instanceId, String region) {
        // TODO: Implement detailed VM info retrieval
        return null;
    }
}
```

#### 6.3 Azure VM Implementation

**AzureVmService.java:**
```java
@Service
@Slf4j
public class AzureVmService implements CloudProviderService {
    
    private final ComputeManager computeManager;
    
    @Autowired
    public AzureVmService(AzureCredentialsService credentialsService) {
        AzureProfile profile = credentialsService.getAzureProfile();
        TokenCredential credential = credentialsService.getAzureCredential();
        this.computeManager = ComputeManager.authenticate(credential, profile);
    }
    
    @Override
    public VmOperationResult startVm(String resourceId, String region) {
        try {
            log.info("Starting Azure VM: {}", resourceId);
            
            VirtualMachine vm = computeManager.virtualMachines().getById(resourceId);
            vm.start();
            
            log.info("Successfully started Azure VM: {}", resourceId);
            return VmOperationResult.success(VmStatus.RUNNING);
            
        } catch (Exception e) {
            log.error("Failed to start Azure VM {}: {}", resourceId, e.getMessage());
            return VmOperationResult.failure(e.getMessage(), "AZURE_START_FAILED");
        }
    }
    
    @Override
    public VmOperationResult stopVm(String resourceId, String region) {
        try {
            log.info("Stopping Azure VM: {}", resourceId);
            
            VirtualMachine vm = computeManager.virtualMachines().getById(resourceId);
            vm.deallocate(); // Deallocate to stop billing
            
            log.info("Successfully stopped Azure VM: {}", resourceId);
            return VmOperationResult.success(VmStatus.STOPPED);
            
        } catch (Exception e) {
            log.error("Failed to stop Azure VM {}: {}", resourceId, e.getMessage());
            return VmOperationResult.failure(e.getMessage(), "AZURE_STOP_FAILED");
        }
    }
    
    @Override
    public VmStatus getVmStatus(String resourceId, String region) {
        try {
            VirtualMachine vm = computeManager.virtualMachines().getById(resourceId);
            PowerState powerState = vm.powerState();
            
            return mapAzureStateToVmStatus(powerState.toString());
            
        } catch (Exception e) {
            log.error("Failed to get Azure VM status {}: {}", resourceId, e.getMessage());
            return VmStatus.ERROR;
        }
    }
    
    @Override
    public List<String> getVmLogs(String resourceId, String region, int lines) {
        // Azure boot diagnostics logs
        try {
            VirtualMachine vm = computeManager.virtualMachines().getById(resourceId);
            // TODO: Implement boot diagnostics log retrieval
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get Azure VM logs: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    private VmStatus mapAzureStateToVmStatus(String azureState) {
        return switch (azureState.toLowerCase()) {
            case "powerstate/running" -> VmStatus.RUNNING;
            case "powerstate/stopped", "powerstate/deallocated" -> VmStatus.STOPPED;
            case "powerstate/starting" -> VmStatus.STARTING;
            case "powerstate/stopping", "powerstate/deallocating" -> VmStatus.STOPPING;
            default -> VmStatus.UNKNOWN;
        };
    }
    
    @Override
    public boolean vmExists(String resourceId, String region) {
        try {
            computeManager.virtualMachines().getById(resourceId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public VmProviderDetails getVmDetails(String resourceId, String region) {
        // TODO: Implement
        return null;
    }
}
```

#### 6.4 Cloud Provider Factory

**CloudProviderFactory.java:**
```java
@Component
public class CloudProviderFactory {
    
    @Autowired
    private AwsEc2Service awsService;
    
    @Autowired
    private AzureVmService azureService;
    
    @Autowired
    private GcpComputeService gcpService;
    
    public CloudProviderService getProvider(CloudProvider provider) {
        return switch (provider) {
            case AWS -> awsService;
            case AZURE -> azureService;
            case GCP -> gcpService;
            default -> throw new UnsupportedProviderException("Provider not supported: " + provider);
        };
    }
}
```

### Testing

**Cloud Provider Integration Tests:**
```java
@SpringBootTest
class CloudProviderIntegrationTest {
    
    @Autowired
    private CloudProviderFactory providerFactory;
    
    @MockBean
    private AmazonEC2 mockEc2Client;
    
    @Test
    void testAwsStartVm() {
        // Mock AWS response
        StartInstancesResult mockResult = new StartInstancesResult();
        when(mockEc2Client.startInstances(any())).thenReturn(mockResult);
        
        CloudProviderService awsService = providerFactory.getProvider(CloudProvider.AWS);
        VmOperationResult result = awsService.startVm("i-1234567890", "us-east-1");
        
        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultingStatus());
    }
    
    @Test
    void testAwsErrorHandling() {
        // Mock AWS error
        when(mockEc2Client.startInstances(any()))
            .thenThrow(new AmazonEC2Exception("InsufficientInstanceCapacity"));
        
        CloudProviderService awsService = providerFactory.getProvider(CloudProvider.AWS);
        VmOperationResult result = awsService.startVm("i-1234567890", "us-east-1");
        
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }
}
```

### Deliverables
- ✅ Cloud provider abstraction interface
- ✅ AWS EC2 implementation
- ✅ Azure VM implementation
- ✅ GCP Compute Engine implementation
- ✅ Provider factory
- ✅ Error handling and retry logic
- ✅ Integration tests with mocked providers

### Definition of Done
- [ ] All three providers implemented
- [ ] Start/stop operations work
- [ ] Status checks functional
- [ ] Error handling tested
- [ ] Retry logic verified

---

**(Continues in next part with Phases 6-11...)**

Would you like me to continue with the remaining phases (VM Operations, Governance, Monitoring, Frontend, Testing, Production)?
