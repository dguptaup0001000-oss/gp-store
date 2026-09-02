package com.gpstore.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The admin dashboard's worker page.
 *
 * Every route here is gated on DELIVERY_MANAGE in SecurityConfig - the
 * permission that already means "runs dispatch". Nothing on this controller
 * reads or writes a customer account, so managing workers can never be a
 * route to somebody's shop login.
 */
@RestController
@RequestMapping("/api/admin/workers")
public class WorkerAdminController {

    private final WorkerAdminService service;

    public WorkerAdminController(WorkerAdminService service) {
        this.service = service;
    }

    /** The write shape. Password is WRITE_ONLY so it can never come back out. */
    public static class WorkerRequest {
        private String name;
        private String mobile;
        private String loginEmail;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String password;

        private String vehicleType;
        private String vehicleNumber;
        private Boolean available;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getMobile() { return mobile; }
        public void setMobile(String mobile) { this.mobile = mobile; }
        public String getLoginEmail() { return loginEmail; }
        public void setLoginEmail(String loginEmail) { this.loginEmail = loginEmail; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getVehicleType() { return vehicleType; }
        public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }
        public String getVehicleNumber() { return vehicleNumber; }
        public void setVehicleNumber(String vehicleNumber) { this.vehicleNumber = vehicleNumber; }
        public Boolean getAvailable() { return available; }
        public void setAvailable(Boolean available) { this.available = available; }

        WorkerAdminService.WorkerForm toForm() {
            return new WorkerAdminService.WorkerForm(
                    name, mobile, loginEmail, password, vehicleType, vehicleNumber, available);
        }

        @Override
        public String toString() {
            return "WorkerRequest{name=" + name + ", loginEmail=" + loginEmail + ", password=***}";
        }
    }

    /** Minutes, so "an hour" and "a day" are one rule on the server, not three. */
    public static class SuspendRequest {
        private Long minutes;
        private String reason;

        public Long getMinutes() { return minutes; }
        public void setMinutes(Long minutes) { this.minutes = minutes; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    @GetMapping
    public List<WorkerAdminService.WorkerView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public WorkerAdminService.WorkerView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public WorkerAdminService.WorkerView create(@RequestBody WorkerRequest request) {
        return service.create(request.toForm());
    }

    @PutMapping("/{id}")
    public WorkerAdminService.WorkerView update(@PathVariable Long id, @RequestBody WorkerRequest request) {
        return service.update(id, request.toForm());
    }

    @PostMapping("/{id}/suspend")
    public WorkerAdminService.WorkerView suspend(@PathVariable Long id, @RequestBody SuspendRequest request) {
        return service.suspend(id, request.getMinutes() == null ? 0 : request.getMinutes(), request.getReason());
    }

    @PostMapping("/{id}/resume")
    public WorkerAdminService.WorkerView resume(@PathVariable Long id) {
        return service.resume(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
