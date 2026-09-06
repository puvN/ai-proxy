package ru.mcs.controlplane.web;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mcs.controlplane.dto.KeyView;
import ru.mcs.controlplane.service.ApiKeyService;
import ru.mcs.controlplane.service.UserService;

@RestController
@RequestMapping("/api/keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserService userService;

    @PostMapping
    public CreateKeyResponse create(Authentication authentication) {
        var user = userService.getCurrent(authentication);
        var created = apiKeyService.createKey(user.getId());
        return new CreateKeyResponse(created.plaintext(), KeyView.from(created.key()));
    }

    @GetMapping
    public List<KeyView> list(Authentication authentication) {
        var user = userService.getCurrent(authentication);
        return apiKeyService.list(user.getId()).stream().map(KeyView::from).toList();
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, Authentication authentication) {
        var user = userService.getCurrent(authentication);
        apiKeyService.revoke(user.getId(), id);
        return ResponseEntity.ok().build();
    }

    public record CreateKeyResponse(String plaintext, KeyView key) {
    }
}
