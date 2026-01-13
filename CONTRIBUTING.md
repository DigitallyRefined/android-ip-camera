# Contributing to Android IP Camera

Thank you for your interest in contributing to the Android IP Camera project! 🎉

This document provides guidelines and information for contributors. Whether you're fixing bugs, adding features, improving documentation, or helping with testing, your contributions are welcome and appreciated.

## 📋 Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [How to Contribute](#how-to-contribute)
- [Development Workflow](#development-workflow)
- [Coding Standards](#coding-standards)
- [Security Considerations](#security-considerations)
- [Testing Guidelines](#testing-guidelines)
- [Submitting Changes](#submitting-changes)
- [Reporting Issues](#reporting-issues)
- [Community](#community)

## 🤝 Code of Conduct

This project follows a code of conduct to ensure a welcoming environment for all contributors. By participating, you agree to:

- **Be respectful** and inclusive in all interactions
- **Focus on constructive feedback** and solutions
- **Respect differing viewpoints** and experiences
- **Accept responsibility** for mistakes and learn from them
- **Show empathy** towards other community members

## 🚀 Getting Started

### Prerequisites
Before you begin, ensure you have:
- **Java JDK 11+** ([Adoptium](https://adoptium.net/temurin/releases/))
- **Android Studio** (latest stable version)
- **OpenSSL** (for certificate generation)
- **Git** (for version control)

### Quick Setup
```cmd
# 1. Fork and clone the repository
git clone https://github.com/YOUR_USERNAME/android-ip-camera.git
cd android-ip-camera

# 2. Set up development environment
build-setup.bat

# 3. Fix Java environment (if needed)
fix-java.bat

# 4. Build and test
build.bat
```

## 🏗️ Development Setup

### Environment Configuration

#### Windows Setup
```cmd
# Automated setup (recommended)
build-setup.bat

# Manual setup if needed
set JAVA_HOME=C:\path\to\jdk
set ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk
```

#### Certificate Setup
```cmd
# For development work
setup.bat

# For personal testing
scripts/setup-personal.bat

# For unit testing
scripts/generate-dev-certificate.bat
```

### IDE Configuration

#### Android Studio Setup
1. **Open Project**: File → Open → Select project root
2. **SDK Setup**: Ensure Android API 24+ is installed
3. **Kotlin Plugin**: Should be included with Android Studio
4. **Build Variants**: Use `debug` for development

#### Recommended Plugins
- **Kotlin**: For language support
- **Android ButterKnife/Zelezny**: For view binding
- **ADB Idea**: For device debugging
- **GitToolBox**: For enhanced Git integration

### Build System

#### Gradle Commands
```cmd
# Build debug APK
./gradlew.bat assembleDebug

# Run unit tests
./gradlew.bat test

# Run linting
./gradlew.bat lint

# Clean build
./gradlew.bat clean

# Full build and test
./gradlew.bat build
```

## 🤝 How to Contribute

### Types of Contributions

#### 🐛 Bug Fixes
- Fix security vulnerabilities
- Resolve crashes and errors
- Improve stability and performance
- Fix UI/UX issues

#### ✨ Feature Enhancements
- Add new camera controls
- Improve streaming quality
- Enhance security features
- Add new configuration options

#### 📚 Documentation
- Improve README and guides
- Add code comments
- Create tutorials and examples
- Update API documentation

#### 🧪 Testing
- Write unit tests
- Create integration tests
- Test on various devices
- Security testing

#### 🌐 Translation
- Add new language support
- Improve existing translations
- Review translation quality

### Finding Issues to Work On

#### Good First Issues
Look for issues labeled:
- `good first issue`
- `beginner-friendly`
- `help wanted`
- `documentation`

#### Priority Issues
- `security` - Security-related fixes
- `bug` - Critical bugs affecting users
- `enhancement` - High-impact features

## 🔄 Development Workflow

### 1. Choose an Issue
```bash
# Check available issues
# Visit: https://github.com/DigitallyRefined/android-ip-camera/issues

# Comment on the issue to indicate you're working on it
# This helps avoid duplicate work
```

### 2. Create a Branch
```bash
# Create and switch to a feature branch
git checkout -b feature/your-feature-name
# OR for bug fixes
git checkout -b fix/issue-number-description
```

### 3. Make Changes
```bash
# Make your changes following coding standards
# Write tests for new functionality
# Update documentation if needed

# Commit regularly with clear messages
git add .
git commit -m "feat: add camera zoom controls"
```

### 4. Test Your Changes
```cmd
# Run tests
./gradlew.bat test

# Build APK and test on device
build.bat

# Test security features
# - Try unauthenticated access (should fail)
# - Test certificate validation
# - Verify rate limiting works
```

### 5. Update Documentation
```bash
# Update README.md if needed
# Update SECURITY_UPGRADES.md for security changes
# Add code comments for complex logic
# Update API documentation
```

### 6. Submit Pull Request
```bash
# Push your branch
git push origin feature/your-feature-name

# Create pull request on GitHub
# Fill out the pull request template
# Link to the issue you're fixing
```

## 📝 Coding Standards

### Kotlin Style Guide

#### General Rules
- **Use `val` instead of `var`** when possible
- **Prefer immutable collections** over mutable ones
- **Use meaningful names** for variables and functions
- **Keep functions small** and focused on single responsibility
- **Use early returns** to reduce nesting

#### Example
```kotlin
// ✅ Good
fun validateUsername(username: String): Boolean {
    if (username.isEmpty()) return false
    if (username.length < 3) return false
    return username.matches(Regex("^[a-zA-Z0-9_-]+$"))
}

// ❌ Avoid
fun validateUsername(username: String): Boolean {
    var isValid = true
    if (username.isEmpty()) {
        isValid = false
    } else if (username.length < 3) {
        isValid = false
    } else if (!username.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
        isValid = false
    }
    return isValid
}
```

### Android-Specific Guidelines

#### Activity/Fragment Lifecycle
- **Avoid memory leaks** by properly managing subscriptions
- **Use View Binding** instead of findViewById
- **Handle configuration changes** appropriately
- **Clean up resources** in onDestroy()

#### Permissions
- **Request permissions** at appropriate times
- **Handle denial gracefully** with user feedback
- **Document permission requirements** in code comments

#### Threading
- **Use coroutines** for asynchronous operations
- **Avoid blocking UI thread** with heavy operations
- **Use appropriate dispatchers** (IO for network, Main for UI)

### Security Standards

#### Credential Handling
- **Never log sensitive data** (passwords, certificates)
- **Use secure storage** for all credentials
- **Validate input** before processing
- **Sanitize output** to prevent injection attacks

#### Network Security
- **Always use HTTPS** (no HTTP fallbacks)
- **Validate certificates** before use
- **Implement rate limiting** for authentication
- **Use secure cipher suites** only

## 🔒 Security Considerations

### Security-First Development

**All contributors must prioritize security. This app handles sensitive camera data and requires enterprise-grade security measures.**

#### Critical Security Rules

1. **Never** allow unauthenticated access
2. **Always** use HTTPS with valid certificates
3. **Never** store credentials in plaintext
4. **Always** validate and sanitize user input
5. **Never** log sensitive information
6. **Always** implement rate limiting

#### Security Checklist for Changes

**Before submitting security-related changes:**
- [ ] Does this change maintain mandatory authentication?
- [ ] Does this change preserve HTTPS-only policy?
- [ ] Does this change protect sensitive data?
- [ ] Does this change include proper input validation?
- [ ] Does this change avoid logging sensitive information?
- [ ] Have you tested the security implications?

#### Security Testing
```kotlin
// Example security test
@Test
fun testUnauthenticatedAccessRejected() {
    // Attempt connection without credentials
    val response = makeRequestWithoutAuth()
    assertEquals(401, response.statusCode)
    assertTrue(response.body.contains("Unauthorized"))
}
```

### Reporting Security Issues

**⚠️ Never report security vulnerabilities publicly!**

- **Email**: security@digitallyrefined.com
- **Response Time**: Within 24 hours
- **Responsible Disclosure**: We follow responsible disclosure practices

## 🧪 Testing Guidelines

### Unit Testing

#### Test Structure
```
src/test/kotlin/com/github/digitallyrefined/androidipcamera/
├── helpers/
│   ├── InputValidatorTest.kt
│   ├── SecureStorageTest.kt
│   └── StreamingServerHelperTest.kt
└── activities/
    └── MainActivityTest.kt
```

#### Example Test
```kotlin
class InputValidatorTest {

    @Test
    fun `valid username passes validation`() {
        val result = InputValidator.validateAndSanitizeUsername("testuser123")
        assertNotNull(result)
        assertEquals("testuser123", result)
    }

    @Test
    fun `invalid username fails validation`() {
        val result = InputValidator.validateAndSanitizeUsername("test@#$%")
        assertNull(result)
    }
}
```

### Integration Testing

#### Android Tests
```kotlin
@RunWith(AndroidJUnit4::class)
class StreamingServerIntegrationTest {

    @Test
    fun testSecureConnection() {
        // Test full HTTPS connection with authentication
        // Verify certificate validation
        // Check rate limiting behavior
    }
}
```

### Manual Testing Checklist

#### Security Testing
- [ ] Attempt unauthenticated access (should fail)
- [ ] Try weak passwords (should be rejected)
- [ ] Test rate limiting (should block after failures)
- [ ] Verify HTTPS enforcement (no HTTP fallback)
- [ ] Test certificate validation

#### Functionality Testing
- [ ] Camera switching (front/rear)
- [ ] Different quality settings
- [ ] Zoom controls
- [ ] Stream scaling
- [ ] Settings persistence

#### Performance Testing
- [ ] Memory usage during streaming
- [ ] Battery impact
- [ ] Network usage
- [ ] Frame rate consistency

## 📤 Submitting Changes

### Pull Request Process

#### 1. Prepare Your Branch
```bash
# Ensure you're on your feature branch
git checkout feature/your-feature-name

# Pull latest changes from main
git pull origin main

# Rebase to keep history clean
git rebase main
```

#### 2. Run Final Checks
```cmd
# Run all tests
./gradlew.bat test

# Build successfully
./gradlew.bat assembleDebug

# Check for lint issues
./gradlew.bat lint
```

#### 3. Create Pull Request

**Pull Request Template:**
```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix (non-breaking change)
- [ ] New feature (non-breaking change)
- [ ] Breaking change
- [ ] Security enhancement
- [ ] Documentation update

## Testing
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Manual testing completed
- [ ] Security testing completed

## Security Impact
- [ ] No security impact
- [ ] Security enhancement
- [ ] Requires security review

## Checklist
- [ ] Code follows style guidelines
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] Security considerations addressed
- [ ] Ready for review
```

#### 4. Address Review Feedback
```bash
# Make requested changes
git add .
git commit -m "fix: address review feedback"

# Push updated branch
git push origin feature/your-feature-name
```

### Commit Message Guidelines

#### Format
```
type(scope): description

[optional body]

[optional footer]
```

#### Types
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation
- `style`: Code style changes
- `refactor`: Code refactoring
- `test`: Testing
- `chore`: Maintenance

#### Examples
```
feat: add camera zoom controls

fix(security): prevent authentication bypass in streaming server

docs: update installation instructions for Windows

test: add unit tests for input validation
```

## 🐛 Reporting Issues

### Bug Reports

**Please provide:**
- **Clear title** describing the issue
- **Steps to reproduce** the problem
- **Expected behavior** vs actual behavior
- **Device information** (Android version, device model)
- **App version** and build type
- **Screenshots/logs** if applicable

### Feature Requests

**Please provide:**
- **Clear description** of the proposed feature
- **Use case** and why it's needed
- **Implementation ideas** if you have them
- **Mockups** or examples if applicable

### Security Issues

**⚠️ DO NOT report security issues publicly!**

- **Email**: security@digitallyrefined.com
- **Include**: Detailed description and reproduction steps
- **Response**: Within 24 hours

## 🌟 Community

### Communication Channels
- **GitHub Issues**: Bug reports and feature requests
- **GitHub Discussions**: General questions and community discussion
- **GitHub Wiki**: Documentation and guides

### Recognition
Contributors are recognized through:
- **GitHub contributor statistics**
- **Release notes credits**
- **Community shoutouts**
- **Co-author commits**

### Getting Help
- **Documentation**: Check README.md and SECURITY_UPGRADES.md
- **Issues**: Search existing issues first
- **Discussions**: Ask the community
- **Wiki**: Browse detailed guides

---

## 🙏 Recognition

Thank you for contributing to Android IP Camera! Your efforts help make this security-focused camera app better for everyone. Whether you're fixing bugs, adding features, or improving documentation, every contribution matters.

**Remember: Security is everyone's responsibility. Let's keep Android IP Camera secure and privacy-focused! 🔒**