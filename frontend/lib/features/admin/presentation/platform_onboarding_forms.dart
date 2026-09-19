import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/platform_models.dart';
import 'platform_providers.dart';

/// Opening a merchant and a shop, from the console rather than from a terminal.
///
/// WHY THESE EXIST. Every route behind them has been on the server, tested,
/// since the marketplace slice landed - but the console only ever LISTED
/// merchants and shops and moved them between statuses. Creating either meant
/// twelve curl calls against /api/platform, which made the one thing only the
/// platform owner can do the one thing the platform owner could not do from
/// the app.
///
/// THE SEQUENCE IS THE SERVER'S, AND THESE FORMS DO NOT SHORTCUT IT. A
/// merchant lands in APPLICATION and has to be walked to APPROVED before a
/// shop can open under it; a shop lands in DRAFT and has to be moved to
/// ACTIVE before it sells. Both rules are enforced server-side and re-checked
/// on every call. What these forms add is that the sequence is now VISIBLE:
/// each one says what it just created, what state it is in, and what the next
/// step is - so the owner is not left guessing why a brand-new merchant
/// cannot have a shop yet.

/// Registers a business.
class PlatformMerchantFormDialog extends ConsumerStatefulWidget {
  const PlatformMerchantFormDialog({super.key});

  @override
  ConsumerState<PlatformMerchantFormDialog> createState() =>
      _PlatformMerchantFormDialogState();
}

class _PlatformMerchantFormDialogState
    extends ConsumerState<PlatformMerchantFormDialog> {
  final _formKey = GlobalKey<FormState>();
  final _legalName = TextEditingController();
  final _displayName = TextEditingController();
  final _phone = TextEditingController();
  final _email = TextEditingController();
  final _ownerId = TextEditingController();
  final _ownerName = TextEditingController();
  final _ownerEmail = TextEditingController();
  final _ownerPhone = TextEditingController();
  // DEFAULTS ON, because the case where the merchant already has an
  // admin-role account is the rare one: before this existed, creating that
  // account needed SQL on the box, so almost nobody has one.
  bool _openLogin = true;
  bool _demo = false;
  bool _saving = false;

  @override
  void dispose() {
    _legalName.dispose();
    _displayName.dispose();
    _phone.dispose();
    _email.dispose();
    _ownerId.dispose();
    _ownerName.dispose();
    _ownerEmail.dispose();
    _ownerPhone.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    final repository = ref.read(platformRepositoryProvider);
    try {
      // THE LOGIN FIRST, because the merchant row wants its id. Opening the
      // account and then failing to register the business leaves one
      // recoverable orphan - an unused staff login - whereas registering
      // the business and then failing to open its login leaves a merchant
      // nobody can administer, which is the state this whole feature exists
      // to stop happening.
      int? ownerId = int.tryParse(_ownerId.text.trim());
      OpenedStaffAccount? opened;
      if (_openLogin) {
        opened = await repository.openStaffAccount(
          fullName: _ownerName.text.trim(),
          email: _ownerEmail.text.trim(),
          mobileNumber: _ownerPhone.text.trim(),
          role: 'ADMIN',
        );
        ownerId = opened.customerId;
      }

      final merchant = await repository.registerMerchant(
            legalName: _legalName.text.trim(),
            displayName: _displayName.text.trim(),
            contactPhone: _phone.text.trim(),
            contactEmail: _email.text.trim(),
            ownerCustomerId: ownerId,
            demo: _demo,
          );
      ref.invalidate(platformMerchantsProvider);
      if (!mounted) return;

      // SHOWN BEFORE THE DIALOG CLOSES, and blocking, because this is the
      // only time this password exists. Dismiss it and it is gone.
      if (opened != null) {
        await showOneTimePassword(context, opened);
        if (!mounted) return;
      }
      Navigator.of(context).pop(merchant);
    } catch (error) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(error))));
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Register a merchant'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // A BUSINESS, NOT A SHOP, and the wording says so because the
              // distinction is the whole reason there are two forms: one
              // business can run several storefronts, and the papers are
              // checked once.
              const Text(
                'The business applies first. It lands as an APPLICATION and '
                'cannot trade or hold a shop until you approve it.',
                style: TextStyle(fontSize: 13),
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _legalName,
                autofocus: true,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(
                  labelText: 'Legal name *',
                  hintText: 'The name on the papers',
                ),
                validator: (value) => (value == null || value.trim().isEmpty)
                    ? 'A merchant needs a legal name'
                    : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _displayName,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(
                  labelText: 'Trading name',
                  hintText: 'What customers see. Defaults to the legal name',
                ),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _phone,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(labelText: 'Contact phone'),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _email,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(labelText: 'Contact email'),
              ),
              const SizedBox(height: 12),
              // WHO WILL SIGN IN, which is the half that used to require SQL
              // and the half whose absence is invisible until somebody tries.
              const Divider(height: 28),
              CheckboxListTile(
                value: _openLogin,
                onChanged: (value) => setState(() => _openLogin = value ?? false),
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                title: const Text("Create the owner's login",
                    style: TextStyle(fontSize: 14)),
                subtitle: const Text(
                  'You get a one-time password to hand over. They must change '
                  'it before they can use the app, and after that you no '
                  'longer have it.',
                  style: TextStyle(fontSize: 12),
                ),
              ),
              if (_openLogin) ...[
                const SizedBox(height: 8),
                TextFormField(
                  controller: _ownerName,
                  textCapitalization: TextCapitalization.words,
                  decoration: const InputDecoration(labelText: "Owner's name *"),
                  validator: (value) => (value == null || value.trim().isEmpty)
                      ? 'A name is required'
                      : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _ownerEmail,
                  keyboardType: TextInputType.emailAddress,
                  decoration: const InputDecoration(
                    labelText: "Owner's email *",
                    helperText: 'This is their login. Refused if an account '
                        'already uses it.',
                    helperMaxLines: 2,
                  ),
                  validator: (value) {
                    final raw = value?.trim() ?? '';
                    if (raw.isEmpty) return 'An email is the login, so it is required';
                    // Deliberately loose: the server owns the real rule, and
                    // a clever regex here only rejects addresses that work.
                    return raw.contains('@') ? null : 'That is not an email address';
                  },
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _ownerPhone,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(labelText: "Owner's phone"),
                ),
              ] else
                // THE ESCAPE HATCH for a merchant who already has an
                // admin-role account. Rare, and getting rarer: before this
                // feature the only way to have one was SQL on the box.
                TextFormField(
                  controller: _ownerId,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'Existing owner account id',
                    helperText: 'The admin account that will run this '
                        "merchant's shops. Leave it out and shops opened "
                        'under this merchant have nobody who can sign in.',
                    helperMaxLines: 4,
                  ),
                  validator: (value) {
                    final raw = value?.trim() ?? '';
                    if (raw.isEmpty) return null;
                    return int.tryParse(raw) == null
                        ? 'An account id is a number'
                        : null;
                  },
                ),
              const SizedBox(height: 4),
              CheckboxListTile(
                value: _demo,
                onChanged: (value) => setState(() => _demo = value ?? false),
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                title: const Text('Demo merchant', style: TextStyle(fontSize: 14)),
                subtitle: const Text(
                  'For testing. Marks the merchant and every shop under it, '
                  'so real trade can be told apart from a rehearsal.',
                  style: TextStyle(fontSize: 12),
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : hapticize(_save),
          child: _saving
              ? const SizedBox(
                  height: 16, width: 16,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Register'),
        ),
      ],
    );
  }
}

/// Onboards a merchant in one screen.
///
/// SIX FIELDS, AND THE LONG WAY ROUND NEEDED FIFTEEN ACROSS TWO TABS AND A
/// STATE MACHINE. Registering a business, walking it through review, opening
/// a shop and opening the owner's login were four separate jobs, and the
/// order mattered in ways nothing on screen explained - approving straight
/// from APPLICATION is refused, and a shop cannot be opened under a merchant
/// that is not approved yet.
///
/// Everything here is something only a person can know. The shop code is
/// derived from the business name, the trading name defaults to the legal
/// one, and the lifecycle is walked server-side with the reason it actually
/// had.
///
/// IT STOPS SHORT OF TRADING, and says so. The shop it opens has empty
/// shelves; switching it on would put a findable storefront with nothing in
/// it in front of customers.
class PlatformOnboardMerchantDialog extends ConsumerStatefulWidget {
  const PlatformOnboardMerchantDialog({super.key});

  /// "26.7606, 83.3732" - exactly what Google Maps copies.
  ///
  /// ONE FIELD, NOT TWO, and it takes the string a person already has on
  /// their clipboard. Two number fields ask somebody to split a value by
  /// hand and get the halves the right way round; this asks for a paste.
  /// Returns null when it is not a usable pair.
  static ({double lat, double lng})? parseLocation(String raw) {
    final parts = raw.trim().split(RegExp(r'[,\s]+'));
    if (parts.length != 2) return null;
    final lat = double.tryParse(parts[0]);
    final lng = double.tryParse(parts[1]);
    if (lat == null || lng == null) return null;
    // A pin outside these is not a place on Earth, and is far more likely to
    // be the two halves swapped than a real coordinate.
    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
    return (lat: lat, lng: lng);
  }

  @override
  ConsumerState<PlatformOnboardMerchantDialog> createState() =>
      _PlatformOnboardMerchantDialogState();
}

class _PlatformOnboardMerchantDialogState
    extends ConsumerState<PlatformOnboardMerchantDialog> {
  final _formKey = GlobalKey<FormState>();
  final _business = TextEditingController();
  final _ownerName = TextEditingController();
  final _ownerEmail = TextEditingController();
  final _ownerPhone = TextEditingController();
  final _location = TextEditingController();
  final _radius = TextEditingController(text: '5');
  bool _saving = false;

  @override
  void dispose() {
    _business.dispose();
    _ownerName.dispose();
    _ownerEmail.dispose();
    _ownerPhone.dispose();
    _location.dispose();
    _radius.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    final where = PlatformOnboardMerchantDialog.parseLocation(_location.text)!;
    setState(() => _saving = true);

    try {
      final opened = await ref.read(platformRepositoryProvider).onboardMerchant(
            businessName: _business.text.trim(),
            ownerName: _ownerName.text.trim(),
            ownerEmail: _ownerEmail.text.trim(),
            ownerPhone: _ownerPhone.text.trim(),
            latitude: where.lat,
            longitude: where.lng,
            maxDeliveryRadiusKm: double.parse(_radius.text.trim()),
          );
      ref.invalidate(platformMerchantsProvider);
      ref.invalidate(platformShopsProvider);
      if (!mounted) return;

      // BLOCKING, AND BEFORE THIS DIALOG CLOSES. This is the only time the
      // password exists.
      await showOneTimePassword(
          context,
          OpenedStaffAccount(
            customerId: opened.ownerCustomerId,
            email: opened.ownerEmail,
            role: 'ADMIN',
            oneTimePassword: opened.oneTimePassword,
            activationCode: opened.activationCode,
          ));
      if (!mounted) return;
      Navigator.of(context).pop(opened);
    } catch (error) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(error))));
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Onboard a merchant'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'This opens the owner\'s login, registers the business, '
                'approves it and opens their shop. You get a one-time '
                'password to hand over.',
                style: TextStyle(fontSize: 13),
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _business,
                autofocus: true,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(
                  labelText: 'Business name *',
                  hintText: 'What customers will see',
                ),
                validator: (value) => (value == null || value.trim().isEmpty)
                    ? 'The business needs a name'
                    : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _ownerName,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(labelText: "Owner's name *"),
                validator: (value) => (value == null || value.trim().isEmpty)
                    ? 'The owner needs a name'
                    : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _ownerEmail,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(
                  labelText: "Owner's email *",
                  helperText: 'This is their login. Refused if an account '
                      'already uses it.',
                  helperMaxLines: 2,
                ),
                validator: (value) {
                  final raw = value?.trim() ?? '';
                  if (raw.isEmpty) {
                    return 'An email is the login, so it is required';
                  }
                  return raw.contains('@') ? null : 'That is not an email address';
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _ownerPhone,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(
                  labelText: "Owner's phone",
                  helperText: 'Optional. Refused if another account uses it.',
                  helperMaxLines: 2,
                ),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _location,
                keyboardType: TextInputType.text,
                decoration: const InputDecoration(
                  labelText: 'Shop location *',
                  hintText: '26.7606, 83.3732',
                  helperText: 'Paste from Google Maps. Customers are matched '
                      'to shops by distance, so a shop with no pin is offered '
                      'to nobody.',
                  helperMaxLines: 3,
                ),
                validator: (value) =>
                    PlatformOnboardMerchantDialog.parseLocation(value ?? '') == null
                    ? 'Paste a latitude and longitude, like 26.7606, 83.3732'
                    : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _radius,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(
                  labelText: 'Delivers up to (km) *',
                  helperText: 'A shop that has not said is offered to nobody.',
                  helperMaxLines: 2,
                ),
                validator: (value) {
                  final km = double.tryParse((value ?? '').trim());
                  if (km == null) return 'How many kilometres?';
                  return km > 0 ? null : 'Must be more than zero';
                },
              ),
              const SizedBox(height: 16),
              // SAYS WHAT IS STILL MISSING, because the owner wanted a shop
              // that sells and is getting one that cannot yet.
              const Text(
                'The shop opens with empty shelves, so it is not trading yet. '
                'The merchant signs in, puts stock up, and then you press '
                'Let them trade.',
                style: TextStyle(fontSize: 12),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : hapticize(_save),
          child: _saving
              ? const SizedBox(
                  height: 16, width: 16,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Onboard'),
        ),
      ],
    );
  }
}

/// Opens a storefront under an approved merchant.
class PlatformShopFormDialog extends ConsumerStatefulWidget {
  const PlatformShopFormDialog({super.key, this.merchants = const []});

  /// The merchants to choose between. Passed in rather than watched so the
  /// list cannot change under the reviewer mid-form.
  final List<MerchantView> merchants;

  @override
  ConsumerState<PlatformShopFormDialog> createState() =>
      _PlatformShopFormDialogState();
}

class _PlatformShopFormDialogState extends ConsumerState<PlatformShopFormDialog> {
  final _formKey = GlobalKey<FormState>();
  final _code = TextEditingController();
  final _displayName = TextEditingController();
  final _latitude = TextEditingController();
  final _longitude = TextEditingController();
  final _radiusKm = TextEditingController(text: '5');
  final _timeZone = TextEditingController(text: 'Asia/Kolkata');
  int? _merchantId;
  bool _saving = false;

  /// The server opens a shop only under these two. Listed here so the form can
  /// SAY SO rather than only relaying the refusal - but the server re-checks,
  /// because a merchant can be suspended between this form opening and being
  /// submitted.
  static bool _canHoldAShop(MerchantView m) =>
      m.status == 'APPROVED' || m.status == 'ACTIVE';

  @override
  void initState() {
    super.initState();
    final eligible = widget.merchants.where(_canHoldAShop).toList();
    if (eligible.length == 1) _merchantId = eligible.first.id;
  }

  @override
  void dispose() {
    _code.dispose();
    _displayName.dispose();
    _latitude.dispose();
    _longitude.dispose();
    _radiusKm.dispose();
    _timeZone.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    final merchantId = _merchantId;
    if (merchantId == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Choose which merchant this shop belongs to.')),
      );
      return;
    }
    setState(() => _saving = true);
    try {
      final shop = await ref.read(platformRepositoryProvider).openShop(
            merchantId: merchantId,
            code: _code.text.trim(),
            displayName: _displayName.text.trim(),
            latitude: double.tryParse(_latitude.text.trim()),
            longitude: double.tryParse(_longitude.text.trim()),
            maxDeliveryRadiusKm: double.tryParse(_radiusKm.text.trim()),
            timeZone: _timeZone.text.trim(),
          );
      ref.invalidate(platformShopsProvider);
      if (!mounted) return;
      Navigator.of(context).pop(shop);
    } catch (error) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(error))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final eligible = widget.merchants.where(_canHoldAShop).toList();

    return AlertDialog(
      title: const Text('Open a shop'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (eligible.isEmpty)
                // NOT AN ERROR, A NEXT STEP. The commonest way to arrive here
                // is having just registered a merchant, which lands in
                // APPLICATION - so the form explains the sequence instead of
                // letting the owner submit and read a conflict.
                const Text(
                  'No merchant is ready to hold a shop yet. A shop can only '
                  'open under a merchant that is APPROVED or ACTIVE - approve '
                  'one on the Merchants tab first.',
                  style: TextStyle(fontSize: 13),
                )
              else ...[
                const Text(
                  'The storefront opens as a DRAFT. Build it, then move it to '
                  'ACTIVE on this tab when it is ready to sell.',
                  style: TextStyle(fontSize: 13),
                ),
                const SizedBox(height: 16),
                DropdownButtonFormField<int>(
                  initialValue: _merchantId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Merchant *'),
                  items: [
                    for (final merchant in eligible)
                      DropdownMenuItem(
                        value: merchant.id,
                        child: Text(
                          '${merchant.displayName ?? merchant.legalName ?? 'Merchant ${merchant.id}'}'
                          '  ·  ${merchant.status}',
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                  onChanged: (value) => setState(() => _merchantId = value),
                  validator: (value) =>
                      value == null ? 'Choose a merchant' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _code,
                  autofocus: true,
                  textCapitalization: TextCapitalization.characters,
                  decoration: const InputDecoration(
                    labelText: 'Shop code *',
                    helperText: 'Short, unique, and permanent - it identifies '
                        'the shop everywhere. Refused if another shop has it.',
                    helperMaxLines: 3,
                  ),
                  validator: (value) => (value == null || value.trim().isEmpty)
                      ? 'A shop needs a code'
                      : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _displayName,
                  textCapitalization: TextCapitalization.words,
                  decoration: const InputDecoration(
                    labelText: 'Shop name',
                    hintText: "What customers see. Defaults to the merchant's name",
                  ),
                ),
                const SizedBox(height: 12),
                // WHERE IT IS AND HOW FAR IT DELIVERS, together, because a
                // shop with coordinates and no radius reaches nobody and a
                // radius with no coordinates reaches everybody.
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _latitude,
                        keyboardType: const TextInputType.numberWithOptions(
                            decimal: true, signed: true),
                        decoration: const InputDecoration(labelText: 'Latitude'),
                        validator: (value) => _optionalNumber(value, 'latitude'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _longitude,
                        keyboardType: const TextInputType.numberWithOptions(
                            decimal: true, signed: true),
                        decoration: const InputDecoration(labelText: 'Longitude'),
                        validator: (value) => _optionalNumber(value, 'longitude'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _radiusKm,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(
                    labelText: 'Delivery radius (km)',
                    helperText: 'How far this shop will deliver. Customers '
                        'outside it never see the storefront.',
                    helperMaxLines: 3,
                  ),
                  validator: (value) => _optionalNumber(value, 'radius'),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _timeZone,
                  decoration: const InputDecoration(
                    labelText: 'Time zone',
                    helperText: "The shop's own hours are read in this zone.",
                    helperMaxLines: 2,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: Text(eligible.isEmpty ? 'Close' : 'Cancel'),
        ),
        if (eligible.isNotEmpty)
          FilledButton(
            onPressed: _saving ? null : hapticize(_save),
            child: _saving
                ? const SizedBox(
                    height: 16, width: 16,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Open as draft'),
          ),
      ],
    );
  }

  static String? _optionalNumber(String? value, String what) {
    final raw = value?.trim() ?? '';
    if (raw.isEmpty) return null;
    return double.tryParse(raw) == null ? 'A $what is a number' : null;
  }
}

/// Puts an existing account on a shop's staff list.
///
/// THE RECOVERY PATH for a merchant registered without an owner account, and
/// the way a second person gets access to a shop. Putting somebody on the
/// staff list is the ONLY way an account gets a tenant scope, so this is the
/// hinge the whole isolation model turns on - which is why it is the
/// platform's call and not a shop's.
class PlatformStaffDialog extends ConsumerStatefulWidget {
  const PlatformStaffDialog({super.key, required this.shop});

  final PlatformShopView shop;

  @override
  ConsumerState<PlatformStaffDialog> createState() => _PlatformStaffDialogState();
}

class _PlatformStaffDialogState extends ConsumerState<PlatformStaffDialog> {
  final _formKey = GlobalKey<FormState>();
  final _customerId = TextEditingController();
  bool _asDefault = true;
  bool _saving = false;

  @override
  void dispose() {
    _customerId.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    try {
      await ref.read(platformRepositoryProvider).addStaff(
            shopId: widget.shop.id,
            customerId: int.parse(_customerId.text.trim()),
            asDefault: _asDefault,
          );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (error) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(error))));
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Staff for ${widget.shop.displayName ?? widget.shop.code ?? 'this shop'}'),
      content: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Being on a shop’s staff list is what gives an account that '
              "shop's data and nothing else. The account must already exist "
              'with an admin role.',
              style: TextStyle(fontSize: 13),
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _customerId,
              autofocus: true,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Account id *'),
              validator: (value) {
                final raw = value?.trim() ?? '';
                if (raw.isEmpty) return 'Which account?';
                return int.tryParse(raw) == null
                    ? 'An account id is a number'
                    : null;
              },
            ),
            const SizedBox(height: 4),
            CheckboxListTile(
              value: _asDefault,
              onChanged: (value) => setState(() => _asDefault = value ?? false),
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text('Make this their home shop',
                  style: TextStyle(fontSize: 14)),
              subtitle: const Text(
                'Where they land when they sign in. Without it, an account '
                'that already has a home shop keeps it.',
                style: TextStyle(fontSize: 12),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : hapticize(_save),
          child: _saving
              ? const SizedBox(
                  height: 16, width: 16,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Add to staff'),
        ),
      ],
    );
  }
}

/// Shows a one-time password, once.
///
/// BLOCKING AND NOT DISMISSIBLE BY TAPPING OUTSIDE. This is the only moment
/// this password exists: the server keeps a bcrypt hash and has no route
/// that returns the plaintext again. A stray tap on the scrim would lose a
/// credential the owner has not written down yet, and the only recovery is
/// a reset - which invalidates the account's sessions and asks the merchant
/// to change it again.
///
/// NOTHING HERE PERSISTS IT. No storage, no file, no clipboard history the
/// app manages. Copy goes to the system clipboard because that is the only
/// way a person moves a 14-character random string into a message - and that
/// is the owner's clipboard, not a store this app keeps.
Future<void> showOneTimePassword(
    BuildContext context, OpenedStaffAccount account) {
  final password = account.oneTimePassword ?? '';
  final code = account.activationCode ?? '';
  return showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (dialogContext) => AlertDialog(
      title: const Text('Hand these over now'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              code.isEmpty
                  ? 'This password is shown once and cannot be looked up '
                      'again. If you lose it, use Reset password on the '
                      'merchant to issue a new one.'
                  : 'These are shown once and cannot be looked up again. If '
                      'either is lost, issue a new one from the merchant - '
                      'there is no way to read the old one back.',
              style: const TextStyle(fontSize: 13),
            ),
            const SizedBox(height: 16),
            _Handover(label: 'Login', value: account.email ?? '—'),
            if (password.isNotEmpty) ...[
              const SizedBox(height: 8),
              _Handover(label: 'One-time password', value: password, mono: true),
            ],
            // THE SECOND HALF OF THE FIRST LOGIN. Both halves travel together
            // or the merchant cannot get in at all - which is exactly why they
            // are on one screen with one "I've saved it".
            if (code.isNotEmpty) ...[
              const SizedBox(height: 8),
              _Handover(label: 'Activation code', value: code, mono: true),
              const SizedBox(height: 8),
              const Text(
                'The activation code is needed only for their FIRST sign-in. '
                'After that they use their email and password.',
                style: TextStyle(fontSize: 12),
              ),
            ],
            const SizedBox(height: 16),
            const Text(
              'They must set their own password before the app will let them '
              'do anything else. After that this one stops working and you no '
              'longer have access to their account.',
              style: TextStyle(fontSize: 12),
            ),
          ],
        ),
      ),
      actions: [
        if (password.isNotEmpty || code.isNotEmpty)
          TextButton.icon(
            onPressed: hapticize(() async {
              // BOTH HALVES IN ONE COPY, because they are useless apart: a
              // merchant sent only the password cannot sign in, and a second
              // copy-paste is a second chance to send the wrong thing.
              final buffer = StringBuffer();
              if (account.email != null) buffer.writeln(account.email);
              if (password.isNotEmpty) buffer.writeln(password);
              if (code.isNotEmpty) buffer.writeln(code);
              await Clipboard.setData(
                  ClipboardData(text: buffer.toString().trim()));
              if (!dialogContext.mounted) return;
              ScaffoldMessenger.of(dialogContext).showSnackBar(
                SnackBar(content: Text(code.isEmpty
                    ? 'Password copied.'
                    : 'Login details copied.')),
              );
            }),
            icon: const Icon(Icons.copy_outlined, size: 18),
            label: Text(code.isEmpty ? 'Copy password' : 'Copy all'),
          ),
        FilledButton(
          onPressed: () => Navigator.of(dialogContext).pop(),
          // NOT "OK". The button has to say that dismissing loses it, because
          // that is what dismissing does.
          child: const Text("I've saved it"),
        ),
      ],
    ),
  );
}

class _Handover extends StatelessWidget {
  const _Handover({required this.label, required this.value, this.mono = false});

  final String label;
  final String value;
  final bool mono;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontSize: 12)),
        const SizedBox(height: 2),
        // SELECTABLE, so it can be long-pressed and copied on a phone where
        // the Copy button is off the bottom of a small dialog.
        SelectableText(
          value,
          style: TextStyle(
            fontSize: mono ? 18 : 15,
            fontWeight: FontWeight.w600,
            fontFamily: mono ? 'monospace' : null,
            letterSpacing: mono ? 1.0 : null,
          ),
        ),
      ],
    );
  }
}

/// Gives a business that has none its first shop.
///
/// THE STATE THIS EXISTS FOR IS A REAL ONE. GUPT SAREE reached production as a
/// merchant row with an owner account and zero shops - registered through
/// "Register a business only", which opens a login and deliberately opens no
/// shop, and then hands over a one-time password. The owner used it, and
/// Merchant Admin told them their account was not associated with a shop.
/// Nothing was broken; there was simply nothing there, and no way back short
/// of deleting the business and starting again.
///
/// It asks only what a shop cannot be without: where it is, and how far it
/// delivers. ShopReadiness calls both blocking, so a shop opened without them
/// looks finished and can never sell.
class PlatformAddFirstShopDialog extends ConsumerStatefulWidget {
  const PlatformAddFirstShopDialog({
    super.key,
    required this.merchantId,
    required this.businessName,
    required this.merchantStatus,
  });

  final int merchantId;
  final String businessName;
  final String? merchantStatus;

  @override
  ConsumerState<PlatformAddFirstShopDialog> createState() =>
      _PlatformAddFirstShopDialogState();
}

class _PlatformAddFirstShopDialogState
    extends ConsumerState<PlatformAddFirstShopDialog> {
  final _formKey = GlobalKey<FormState>();
  final _shopName = TextEditingController();
  final _location = TextEditingController();
  final _radius = TextEditingController(text: '5');
  bool _saving = false;

  /// True when opening the shop will also move the business forward, so the
  /// dialog can say so BEFORE it happens rather than after.
  bool get _willApprove =>
      widget.merchantStatus == 'APPLICATION' ||
      widget.merchantStatus == 'PENDING_REVIEW';

  @override
  void dispose() {
    _shopName.dispose();
    _location.dispose();
    _radius.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    final where = PlatformOnboardMerchantDialog.parseLocation(_location.text)!;
    setState(() => _saving = true);
    try {
      final made = await ref.read(platformRepositoryProvider).addFirstShop(
            merchantId: widget.merchantId,
            displayName: _shopName.text.trim(),
            latitude: where.lat,
            longitude: where.lng,
            maxDeliveryRadiusKm: double.parse(_radius.text.trim()),
          );
      ref.invalidate(platformMerchantsProvider);
      ref.invalidate(platformShopsProvider);
      if (!mounted) return;
      Navigator.of(context).pop(made);
    } catch (error) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(extractErrorMessage(error))));
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add the first shop'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${widget.businessName} has no shop, so its owner has nothing '
                'to sign in to. This opens one and makes the owner its first '
                'member.',
                style: const TextStyle(fontSize: 13),
              ),
              if (_willApprove) ...[
                const SizedBox(height: 8),
                // SAID IN ADVANCE, because it changes the business and not
                // only the shop. A shop can only be opened under an approved
                // merchant, so this is what is actually in the way.
                const Text(
                  'This business will also be approved, because a shop can '
                  'only be opened under an approved one. The shop still opens '
                  'as a draft, so nothing goes in front of customers yet.',
                  style: TextStyle(fontSize: 13, fontStyle: FontStyle.italic),
                ),
              ],
              const SizedBox(height: 16),
              TextFormField(
                controller: _shopName,
                autofocus: true,
                textCapitalization: TextCapitalization.words,
                decoration: InputDecoration(
                  labelText: 'Shop name',
                  hintText: widget.businessName,
                  helperText: 'Leave blank to use the business name',
                ),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _location,
                decoration: const InputDecoration(
                  labelText: 'Location *',
                  hintText: '26.76, 83.37',
                  helperText: 'Latitude, longitude',
                ),
                validator: (value) =>
                    PlatformOnboardMerchantDialog.parseLocation(value ?? '') ==
                            null
                        ? 'Two numbers: latitude, longitude'
                        : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _radius,
                keyboardType: const TextInputType.numberWithOptions(
                    decimal: true),
                inputFormatters: [
                  FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
                ],
                decoration: const InputDecoration(
                  labelText: 'Delivery radius (km) *',
                  helperText: 'A shop that has not said is offered to nobody',
                ),
                validator: (value) {
                  final km = double.tryParse((value ?? '').trim());
                  if (km == null || km <= 0) return 'How far will it deliver?';
                  return null;
                },
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : hapticize(_save),
          child: Text(_saving ? 'Opening...' : 'Open the shop'),
        ),
      ],
    );
  }
}
