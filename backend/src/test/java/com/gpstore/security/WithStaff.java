package com.gpstore.security;

import com.gpstore.entity.Role;
import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Authenticates a test as a staff member of the given role.
 *
 * <p>WHY NOT {@code @WithMockUser(roles = "ADMIN")}. That grants exactly
 * ROLE_ADMIN and nothing else, and SecurityConfig now gates on PERM_
 * authorities - so a test using it would 403 on every admin route and prove
 * only that the annotation is wrong.
 *
 * <p>WHY NOT {@code @WithMockUser(authorities = {"ROLE_ADMIN", "PERM_..."})}
 * with the list written out. The list would be a second copy of
 * {@link RolePermissions}, and the first time somebody adds a permission the
 * two would disagree - tests passing against a permission set production does
 * not actually grant. This annotation computes its authorities from
 * RolePermissions, the same class JwtFilter uses, so the two cannot drift.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@WithSecurityContext(factory = WithStaffSecurityContextFactory.class)
public @interface WithStaff {

    /** Defaults to ADMIN, which is what the tests this replaced all used. */
    Role value() default Role.ADMIN;

    String username() default "staff@example.test";
}
