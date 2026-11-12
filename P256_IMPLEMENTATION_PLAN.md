# P-256 Safe Owner Implementation Plan

## Overview
Implement P-256 (secp256r1) key support for Safe{Wallet} owners using the WebAuthn signer pattern from safe-modules.

## Architecture Changes

### 1. Domain Layer (`:domain`)

#### New Account Kind
**File:** `domain/src/commonMain/kotlin/eth/serotine/domain/model/Account.kt`

```kotlin
enum class AccountKind {
    EOA,                    // secp256k1 from mnemonic
    SAFE_P256,             // P-256 in Secure Enclave/StrongBox via WebAuthn
}
```

#### New Custody Type
**File:** `domain/src/commonMain/kotlin/eth/serotine/domain/model/Account.kt`

```kotlin
sealed interface AccountCustody {
    data object Watched : AccountCustody
    data object InKeyStore : AccountCustody  // secp256k1
    data object HardwareBound : AccountCustody  // P-256 hardware key
}
```

#### Key Import Result for P-256
**File:** `domain/src/commonMain/kotlin/eth/serotine/domain/repository/KeyStore.kt`

Add to `KeyImport`:
```kotlin
sealed interface KeyImport {
    // ... existing variants ...
    
    /** P-256 key generated in hardware, public key [x], [y] coordinates. */
    data class HardwareP256Generated(
        val x: String,  // 64 hex chars
        val y: String,  // 64 hex chars
    ) : KeyImport
}
```

### 2. Data Layer (`:data`)

#### WebAuthn Signature Support
**File:** `data/src/commonMain/kotlin/eth/serotine/data/model/WebAuthnSignature.kt` (new)

```kotlin
package eth.serotine.data.model

/**
 * A WebAuthn-formatted P-256 signature for Safe transactions.
 * Format: authenticatorData ‖ clientDataFields ‖ r ‖ s
 */
@ConsistentCopyVisibility
data class WebAuthnSignature(
    val authenticatorData: ByteArray,
    val clientDataFields: String,
    val r: String,  // 64 hex chars
    val s: String,  // 64 hex chars
) {
    fun encode(): ByteArray = authenticatorData + clientDataFields.encodeToByteArray() + r.hexToBytes() + s.hexToBytes()
}
```

#### Hardware Signer Interface
**File:** `data/src/commonMain/kotlin/eth/serotine/data/repository/HardwareSigner.kt` (new)

```kotlin
package eth.serotine.data.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.Signing

/**
 * A signer that uses hardware-bound P-256 keys via platform WebAuthn APIs.
 * Keys never leave the Secure Enclave/StrongBox.
 */
interface HardwareSigner {
    /**
     * Signs [digest] with the P-256 key at [owner]'s x,y coordinates.
     * Returns WebAuthn-formatted signature.
     */
    suspend fun signP256(owner: Address, x: String, y: String, digest: Digest): Signing
}
```

#### Platform Implementations

**Android (`data/src/androidMain/kotlin/eth/serotine/data/keys/AndroidHardwareSigner.kt`):**
- Use Android Keystore P-256 key
- `setUserAuthenticationRequired(true)` with biometric auth
- Sign using `Signature.SIGNATURE_ECDSA`

**iOS/macOS (`data/src/iosMain/kotlin/eth/serotine/data/keys/IosHardwareSigner.kt`):**
- Use Security.framework P-256 key in Secure Enclave
- `SecKeyCreateSignature()` with `kSecKeyAlgorithmECDSASignatureDigestX962SHA256`
- Biometric auth via `LAContext`

### 3. UI Layer (`:ui`)

#### New Account Custody Display
**File:** `ui/src/commonMain/kotlin/eth/serotine/ui/components/AccountItem.kt`

Update to show `HardwareBound` custody differently:
```kotlin
when (account.custody) {
    is AccountCustody.HardwareBound -> "Hardware-bound P-256"
    // ... existing ...
}
```

#### Safe Owner Management

**New Screen:** Add "Add Hardware Owner" flow in Safe setup / settings

**File:** `ui/src/commonMain/kotlin/eth/serotine/ui/safeowner/SafeOwnerRoute.kt` (new)

This extends the existing partial sign flow:
1. User selects "Add hardware owner"
2. Platform generates P-256 key in hardware
3. Public key (x, y) is displayed as QR code or text
4. User shares this with another signer to add as Safe owner
5. Once added, the Safe uses WebAuthn signer proxy

### 4. Key Store Changes

**File:** `data/src/commonMain/kotlin/eth/serotine/data/keys/EnvelopeKeyStore.kt`

Add P-256 key generation:
```kotlin
override suspend fun newHardwareP256Key(): NewHardwareP256Key {
    // Platform-specific key generation
    // Returns x, y coordinates of public key
}
```

## WebAuthn Signer Integration

### Safe Creation with P-256 Owner

When creating a Safe with a hardware-bound P-256 owner:

1. **SafeWebAuthnSignerFactory** deploys a proxy for this owner
2. Proxy stores: public key coordinates (x, y), verifier contract address
3. Safe's `setup()` includes this as an owner with threshold

### Transaction Signing Flow

```
1. User taps "Sign" on Safe transaction
2. Screen prompts for biometric (or device credential)
3. Platform signs EIP-712 hash using P-256 key
4. Signature formatted as WebAuthn:
   - authenticatorData (from hardware)
   - clientDataFields (JSON with challenge, type)
   - r, s (ECDSA components)
5. Signature included in Safe's execTransaction call
```

## Implementation Steps

### Phase 1: Core Infrastructure
1. Add `AccountKind.SAFE_P256` and `AccountCustody.HardwareBound`
2. Create `WebAuthnSignature` model
3. Create `HardwareSigner` interface and platform implementations

### Phase 2: Key Generation
4. Add `newHardwareP256Key()` to `KeyStore`
5. Implement iOS/macOS key generation (Secure Enclave)
6. Implement Android key generation (Keystore)

### Phase 3: Safe Integration
7. Create `SafeWebAuthnSignerProxy` deployment logic
8. Update `SafeCreation` to support P-256 owners
9. Update `SigningRequestCodec` to handle WebAuthn signatures

### Phase 4: UI
10. Add "Add Hardware Owner" flow (extends partial sign)
11. Display P-256 accounts differently in Accounts list
12. Safe transaction signing with hardware prompt

### Phase 5: Testing
13. Unit tests for `HardwareSigner` implementations
14. UI tests for new flows
15. Integration tests for Safe creation with P-256 owner

## Platform-Specific Details

### iOS/macOS (Priority 1)
- **API:** Security.framework
- **Key Type:** `kSecAttrKeyClassPrivate`, `kSecAttrKeyTypeECSECPrimeRandom`
- **Access Control:** `.privateKeyUsage | .biometryCurrentSet | .whenPasscodeSetThisDeviceOnly`
- **Signing:** `SecKeyCreateSignature(..., kSecKeyAlgorithmECDSASignatureDigestX962SHA256)`

### Android (Priority 2)
- **API:** Keystore with `KeyProperties.ECDSA_P256`
- **Block Mode:** `KeyProperties.BLOCK_MODE_ECB` (no padding for raw ECDSA)
- **Authentication:** `setUserAuthenticationRequired(true)` + biometric
- **Signing:** `Signature.SIGNATURE_ECDSA`

## Addresses to Use

From safe-modules:
- **SafeWebAuthnSignerSingleton** - deployed on Sepolia, Gnosis, Mainnet
- **SafeWebAuthnSignerFactory** - creates proxies per owner

## Open Questions

1. **Address derivation:** How to map P-256 public key to Safe owner address?
   - Option A: Use the WebAuthn credential ID (not deterministic)
   - Option B: Derive from public key hash (like EOA but with P-256)

2. **Verifier contract:** Which P-256 verifier to use?
   - Safe's built-in precompile at `0x100` (when available)
   - Fallback Solidity implementation

3. **Biometric policy:** `.biometryCurrentSet` (invalidates on re-enrollment) or
   `.biometryCurrentSet | .devicePasscode` (fallback to passcode)?
