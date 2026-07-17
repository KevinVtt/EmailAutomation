package com.emailfilter.service;

import com.emailfilter.dto.AuthResponse;
import com.emailfilter.exception.ResourceNotFoundException;
import com.emailfilter.model.User;
import com.emailfilter.repository.UserRepository;
import com.emailfilter.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public AuthResponse authenticateOAuth2User(String provider, String email, String name,
                                                String providerId, String avatarUrl) {
        var user = userRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> createUser(provider, email, name, providerId, avatarUrl));

        var accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return AuthResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
    }

    public AuthResponse refreshAccessToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new ResourceNotFoundException("Invalid refresh token");
        }
        var userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        var newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        var newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return AuthResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    private User createUser(String provider, String email, String name,
                            String providerId, String avatarUrl) {
        var user = User.builder()
                .email(email)
                .name(name)
                .provider(provider)
                .providerId(providerId)
                .avatarUrl(avatarUrl)
                .build();
        return userRepository.save(user);
    }
}
