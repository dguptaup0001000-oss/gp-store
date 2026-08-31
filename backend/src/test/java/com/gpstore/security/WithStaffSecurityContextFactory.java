package com.gpstore.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the same authorities JwtFilter would build for that role.
 *
 * <p>Deliberately mirrors JwtFilter's shape - ROLE_&lt;name&gt; plus one
 * PERM_&lt;name&gt; per permission - by calling the same
 * {@link RolePermissions} lookup. If production stops granting a permission,
 * these tests stop having it too, which is the entire point.
 *
 * <p>The principal is a plain Spring Security {@code User}, matching what
 * {@code @WithMockUser} produced, so the tests this annotation replaced see
 * the principal shape they always did.
 */
public class WithStaffSecurityContextFactory
        implements WithSecurityContextFactory<WithStaff> {

    @Override
    public SecurityContext createSecurityContext(WithStaff annotation) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + annotation.value().name()));
        for (AdminPermission permission : RolePermissions.forRole(annotation.value())) {
            authorities.add(new SimpleGrantedAuthority(permission.authority()));
        }

        User principal = new User(annotation.username(), "", authorities);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, principal.getPassword(), authorities));
        return context;
    }
}
