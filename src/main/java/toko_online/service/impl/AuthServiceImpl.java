package toko_online.service.impl;

import at.favre.lib.crypto.bcrypt.BCrypt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import toko_online.exception.DatabaseException;
import toko_online.exception.UnauthorizedException;
import toko_online.exception.ValidationException;
import toko_online.model.dto.request.LoginRequest;
import toko_online.model.dto.request.RegisterRequest;
import toko_online.model.dto.response.LoginResponse;
import toko_online.model.dto.response.UserResponse;
import toko_online.model.entity.User;
import toko_online.model.enums.Role;
import toko_online.repository.UserRepository;
import toko_online.security.TokenProvider;
import toko_online.service.AuthService;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final String BEARER_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final TokenProvider jwtService;

    public AuthServiceImpl(UserRepository userRepository, TokenProvider jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        log.info("Menerima permintaan login untuk identifier: {}", request != null ? request.getUserEmail() : "null");

        if (request == null) {
            throw new ValidationException("Data login tidak boleh kosong.");
        }
        if (request.getUserEmail() == null || request.getUserEmail().trim().isEmpty()) {
            throw new ValidationException("Email wajib diisi.");
        }
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new ValidationException("Password wajib diisi.");
        }

        String identifier = request.getUserEmail().trim();
        User user = userRepository.findByEmail(identifier)
                .or(() -> userRepository.findByUsername(identifier))
                .orElseThrow(() -> {
                    log.warn("Login gagal: User '{}' tidak ditemukan.", identifier);
                    return new UnauthorizedException("Email atau password salah.");
                });

        BCrypt.Result result = BCrypt.verifyer().verify(
                request.getPassword().toCharArray(),
                user.getPassword().toCharArray());

        if (!result.verified) {
            log.warn("Login gagal: Password tidak cocok untuk user '{}'.", user.getUsername());
            throw new UnauthorizedException("Email atau password salah.");
        }

        return buildLoginResponse(user);
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        log.info("Menerima permintaan registrasi user baru: {}", request != null ? request.getUsername() : "null");

        if (request == null) {
            throw new ValidationException("Data registrasi tidak boleh kosong.");
        }
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            throw new ValidationException("Username wajib diisi.");
        }
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new ValidationException("Email wajib diisi.");
        }
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new ValidationException("Password wajib diisi.");
        }

        if (userRepository.existsByUsername(request.getUsername().trim())) {
            throw new ValidationException("Username '" + request.getUsername() + "' sudah terdaftar.");
        }
        if (userRepository.existsByEmail(request.getEmail().trim())) {
            throw new ValidationException("Email '" + request.getEmail() + "' sudah terdaftar.");
        }

        String hashedPassword = BCrypt.withDefaults().hashToString(12, request.getPassword().toCharArray());

        User user = new User(
                request.getUsername().trim(),
                hashedPassword,
                request.getEmail().trim(),
                Role.USER,
                request.getPhoneNumber(),
                request.getAddress(),
                request.getPosCode());

        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (DatabaseException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                throw new ValidationException("Username atau email sudah terdaftar.");
            }
            throw e;
        }
        log.info("User baru berhasil didaftarkan: {} (ID: {})", savedUser.getUsername(), savedUser.getId());

        return new UserResponse(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getEmail(),
                savedUser.getRole(),
                savedUser.getPhoneNumber(),
                savedUser.getAddress(),
                savedUser.getPosCode());
    }

    @Override
    public LoginResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ValidationException("Refresh token wajib diisi.");
        }
        if (!jwtService.isTokenValid(refreshToken)) {
            throw new UnauthorizedException("Refresh token tidak valid atau kadaluarsa.");
        }
        Long userId = jwtService.extractUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User tidak ditemukan."));
        return buildLoginResponse(user);
    }

    private LoginResponse buildLoginResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        log.info("Login berhasil untuk user: {} (ID: {})", user.getUsername(), user.getId());
        return new LoginResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getRole().name(),
                accessToken,
                refreshToken,
                BEARER_TYPE,
                jwtService.getAccessTokenExpirationSeconds());
    }
}
