package ru.embroidery.shop.auth

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.conflict
import java.sql.ResultSet
import java.util.UUID

data class StaffAccount(val id: UUID, val email: String, val canManageStaff: Boolean)
data class StaffAccountList(val currentEmail: String, val items: List<StaffAccount>)
data class StaffAccountSetup(val account: StaffAccount, val totpSecret: String)
data class NewStaffAccount(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    val canManageStaff: Boolean = false
)

internal class StaffLogin(val credentialId: UUID, val canManageStaff: Boolean, val totp: StaffTotp)

@Service
class StaffAccounts(
    private val db: JdbcClient,
    private val keys: StaffTotpKeys,
    environment: Environment
) {
    private val production = environment.acceptsProfiles(Profiles.of("production"))

    @Transactional
    fun initialize(bootstrap: () -> Map<String, String>) {
        lockChanges()
        val existingManager = db.sql("""
            SELECT credential_id, encrypted_totp FROM staff_accounts WHERE can_manage_staff LIMIT 1
        """).query { row, _ -> keys.decrypt(row.uuid("credential_id"), row.getBytes("encrypted_totp")) }
            .optional()
        if (existingManager.isPresent) return
        check(!db.sql("SELECT EXISTS (SELECT 1 FROM staff_accounts)").query(Boolean::class.java).single()) {
            "Staff accounts exist without an administrator"
        }
        bootstrap().forEach { (email, secret) -> insert(email, true, secret) }
    }

    fun credentialId(email: String): UUID? = db.sql("SELECT credential_id FROM staff_accounts WHERE email = :email")
        .param("email", email).query(UUID::class.java).optional().orElse(null)

    // Keep verification and the credential change ordered until the login transaction ends.
    internal fun login(email: String): StaffLogin? = db.sql("""
        SELECT credential_id, can_manage_staff, encrypted_totp FROM staff_accounts WHERE email = :email FOR SHARE
    """).param("email", email).query { row, _ ->
        val credentialId = row.uuid("credential_id")
        StaffLogin(credentialId, row.getBoolean("can_manage_staff"), keys.decrypt(credentialId, row.getBytes("encrypted_totp")))
    }.optional().orElse(null)

    fun sessionIsActive(actor: Actor): Boolean =
        actor.role == Role.CUSTOMER || actor.staffCredentialId?.let { credentialId(it, manager = false) } == true

    fun list(actor: Actor): StaffAccountList = StaffAccountList(actor.email, db.sql("""
        SELECT id, email, can_manage_staff FROM staff_accounts ORDER BY email
    """).query { row, _ -> account(row) }.list())

    @Transactional
    fun create(actor: Actor, input: NewStaffAccount): StaffAccountSetup {
        lockChanges()
        requireManager(actor)
        val email = normalizedEmail(input.email)
        if (production && email.endsWith(".test")) badRequest("Use a working staff email address")
        if (credentialId(email) != null) conflict("Staff email already exists")
        val secret = keys.generate()
        return StaffAccountSetup(insert(email, input.canManageStaff, secret), secret)
    }

    @Transactional
    fun reset(actor: Actor, id: UUID): StaffAccountSetup {
        lockChanges()
        requireManager(actor)
        val account = otherAccount(actor, id)
        val secret = keys.generate()
        val credentialId = UUID.randomUUID()
        db.sql("""
            UPDATE staff_accounts SET credential_id = :credentialId, encrypted_totp = :totp WHERE id = :id
        """).param("credentialId", credentialId).param("totp", keys.encrypt(credentialId, secret))
            .param("id", id).update()
        clearTotpUse(account.email)
        return StaffAccountSetup(account, secret)
    }

    @Transactional
    fun remove(actor: Actor, id: UUID) {
        lockChanges()
        requireManager(actor)
        val account = otherAccount(actor, id)
        db.sql("DELETE FROM staff_accounts WHERE id = :id").param("id", id).update()
        clearTotpUse(account.email)
    }

    private fun insert(email: String, canManageStaff: Boolean, secret: String): StaffAccount {
        val credentialId = UUID.randomUUID()
        return db.sql("""
            INSERT INTO staff_accounts (email, can_manage_staff, credential_id, encrypted_totp)
            VALUES (:email, :canManageStaff, :credentialId, :totp)
            RETURNING id, email, can_manage_staff
        """).param("email", email).param("canManageStaff", canManageStaff)
            .param("credentialId", credentialId).param("totp", keys.encrypt(credentialId, secret))
            .query { row, _ -> account(row) }.single()
    }

    private fun lockChanges() {
        db.sql("LOCK TABLE staff_accounts IN SHARE ROW EXCLUSIVE MODE").update()
    }

    private fun requireManager(actor: Actor) {
        if (actor.staffCredentialId?.let { credentialId(it, manager = true) } != true) {
            throw ApiFailure(HttpStatus.FORBIDDEN, "Staff management access is required")
        }
    }

    private fun credentialId(id: UUID, manager: Boolean): Boolean = db.sql("""
        SELECT EXISTS (
            SELECT 1 FROM staff_accounts WHERE credential_id = :id AND (NOT :manager OR can_manage_staff)
        )
    """).param("id", id).param("manager", manager).query(Boolean::class.java).single()

    private fun otherAccount(actor: Actor, id: UUID): StaffAccount {
        val target = db.sql("SELECT id, email, can_manage_staff FROM staff_accounts WHERE id = :id")
            .param("id", id).query { row, _ -> account(row) }.optional().orElse(null)
            ?: throw ApiFailure(HttpStatus.NOT_FOUND, "Staff account not found")
        if (target.email == actor.email) conflict("You cannot change your own staff access")
        return target
    }

    private fun clearTotpUse(email: String) {
        db.sql("DELETE FROM staff_totp_use WHERE email = :email").param("email", email).update()
    }

    private fun account(row: ResultSet) =
        StaffAccount(row.uuid("id"), row.getString("email"), row.getBoolean("can_manage_staff"))

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
}

@RestController
class StaffAccountController(private val accounts: StaffAccounts) {
    @GetMapping("/api/staff/session")
    fun session(@AuthenticationPrincipal actor: Actor) = mapOf("email" to actor.email, "canManageStaff" to actor.canManageStaff)

    @GetMapping("/api/staff/accounts")
    fun list(@AuthenticationPrincipal actor: Actor) = accounts.list(actor)

    @PostMapping("/api/staff/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal actor: Actor, @Valid @RequestBody input: NewStaffAccount) =
        accounts.create(actor, input)

    @PostMapping("/api/staff/accounts/{id}/reset-totp")
    fun reset(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = accounts.reset(actor, id)

    @DeleteMapping("/api/staff/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = accounts.remove(actor, id)
}
