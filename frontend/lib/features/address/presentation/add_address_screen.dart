import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';

import '../../auth/presentation/auth_providers.dart';
import '../domain/address_models.dart';
import 'address_providers.dart';

class AddAddressScreen extends ConsumerStatefulWidget {
  const AddAddressScreen({super.key, this.existingAddress});

  /// Null means "add a new address" - non-null means editing this one.
  final AddressModel? existingAddress;

  @override
  ConsumerState<AddAddressScreen> createState() => _AddAddressScreenState();
}

class _AddAddressScreenState extends ConsumerState<AddAddressScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _fullNameController;
  late final TextEditingController _mobileController;
  late final TextEditingController _houseNoController;
  late final TextEditingController _areaController;
  late final TextEditingController _landmarkController;
  late final TextEditingController _directionsController;
  late final TextEditingController _cityController;
  late final TextEditingController _stateController;
  late final TextEditingController _pincodeController;

  double? _latitude;
  double? _longitude;
  bool _isFetchingLocation = false;
  bool _isPrefilling = false;
  bool _isSaving = false;

  bool get _isEditing => widget.existingAddress != null;

  @override
  void initState() {
    super.initState();
    final a = widget.existingAddress;
    _fullNameController = TextEditingController(text: a?.fullName ?? '');
    _mobileController = TextEditingController(text: a?.mobileNumber ?? '');
    _houseNoController = TextEditingController(text: a?.houseNo ?? '');
    _areaController = TextEditingController(text: a?.area ?? '');
    _landmarkController = TextEditingController(text: a?.landmark ?? '');
    _directionsController =
        TextEditingController(text: a?.deliveryInstructions ?? '');
    _cityController = TextEditingController(text: a?.city ?? '');
    _stateController = TextEditingController(text: a?.state ?? '');
    _pincodeController = TextEditingController(text: a?.pincode ?? '');
    // Editing an existing address starts with its saved coordinates already
    // "captured" - the customer only needs to tap "Use my location" again
    // if they've actually moved, not every time they fix a typo.
    _latitude = a?.latitude;
    _longitude = a?.longitude;
  }

  @override
  void dispose() {
    _fullNameController.dispose();
    _mobileController.dispose();
    _houseNoController.dispose();
    _areaController.dispose();
    _landmarkController.dispose();
    _directionsController.dispose();
    _cityController.dispose();
    _stateController.dispose();
    _pincodeController.dispose();
    super.dispose();
  }

  /// Real device GPS - the backend's deliverability/distance calculation
  /// needs actual coordinates, not a guess. A full interactive map picker
  /// (drag a pin) would be the eventual upgrade here; this is the genuine,
  /// working version for now, not a placeholder.
  Future<void> _useMyLocation() async {
    setState(() => _isFetchingLocation = true);

    try {
      bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        throw Exception('Please turn on location services');
      }

      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) {
          throw Exception('Location permission denied');
        }
      }
      if (permission == LocationPermission.deniedForever) {
        throw Exception('Location permission permanently denied - enable it in app settings');
      }

      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.high),
      );

      if (!mounted) return;
      setState(() {
        _latitude = position.latitude;
        _longitude = position.longitude;
      });
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Location captured')));

      // FILL THE FORM FROM THE PIN, so the customer edits instead of types.
      //
      // Deliberately AFTER the pin is already saved to state and the
      // confirmation shown: the capture has succeeded whatever the geocoder
      // does next, and a customer must never be left waiting on somebody
      // else's server to find out whether their location worked.
      await _prefillFromPin(position.latitude, position.longitude);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString().replaceFirst('Exception: ', ''))));
    } finally {
      if (mounted) setState(() => _isFetchingLocation = false);
    }
  }

  /// Pre-fills the empty boxes from the pin. Never overwrites typing.
  ///
  /// ONLY WHAT IS BLANK. Someone who has already written their area does not
  /// want it replaced by a geocoder's guess - and in a village the geocoder is
  /// often the one that is wrong. This is why the customer's own words always
  /// win, including the words they wrote before pressing the button.
  ///
  /// SILENT WHEN IT FINDS NOTHING. An empty answer is normal for a lane with
  /// no name, and telling somebody "we could not look up your address" about a
  /// convenience they did not ask for would be noise.
  Future<void> _prefillFromPin(double latitude, double longitude) async {
    setState(() => _isPrefilling = true);
    try {
      final suggestion = await ref
          .read(addressRepositoryProvider)
          .reverseGeocode(latitude: latitude, longitude: longitude);
      if (!mounted || suggestion.isEmpty) return;

      void fillIfEmpty(TextEditingController controller, String? value) {
        if (value == null || value.trim().isEmpty) return;
        if (controller.text.trim().isNotEmpty) return;
        controller.text = value.trim();
      }

      fillIfEmpty(_areaController, suggestion['area'] ?? suggestion['street']);
      fillIfEmpty(_cityController, suggestion['city']);
      fillIfEmpty(_stateController, suggestion['state']);
      fillIfEmpty(_pincodeController, suggestion['pincode']);

      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Filled in what we could - please check and correct it'),
      ));
    } finally {
      if (mounted) setState(() => _isPrefilling = false);
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    if (_latitude == null || _longitude == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please capture your location - required for delivery')),
      );
      return;
    }

    setState(() => _isSaving = true);

    final model = AddressModel(
      id: widget.existingAddress?.id,
      fullName: _fullNameController.text.trim(),
      mobileNumber: _mobileController.text.trim(),
      houseNo: _houseNoController.text.trim(),
      area: _areaController.text.trim(),
      landmark: _landmarkController.text.trim().isEmpty ? null : _landmarkController.text.trim(),
      deliveryInstructions: _directionsController.text.trim().isEmpty
          ? null
          : _directionsController.text.trim(),
      city: _cityController.text.trim(),
      state: _stateController.text.trim(),
      pincode: _pincodeController.text.trim(),
      latitude: _latitude!,
      longitude: _longitude!,
      defaultAddress: widget.existingAddress?.defaultAddress ?? false,
    );

    try {
      if (_isEditing) {
        await ref.read(addressRepositoryProvider).updateAddress(model);
      } else {
        await ref.read(addressRepositoryProvider).createAddress(model);
      }

      ref.invalidate(myAddressesProvider);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEditing ? 'Edit Address' : 'Add Address')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                OutlinedButton.icon(
                  onPressed: _isFetchingLocation ? null : _useMyLocation,
                  icon: _isFetchingLocation
                      ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.my_location),
                  label: Text(
                    _latitude != null ? 'Location captured ✓' : 'Use my current location',
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  _latitude == null
                      ? 'Required. We use your pin to check we deliver to this address. Typing the address is not enough.'
                      : _isPrefilling
                          ? 'Pin saved. Filling in the address from your location...'
                          : 'Pin saved. Checkout can calculate delivery from this location.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 16),
                _field(_fullNameController, 'Full name'),
                _field(_mobileController, 'Mobile number', keyboardType: TextInputType.phone),
                _field(_houseNoController, 'House / Flat No.'),
                _field(_areaController, 'Area / Street'),
                _field(_landmarkController, 'Landmark (optional)', required: false),
                _field(_cityController, 'City'),
                _field(_stateController, 'State'),
                _field(_pincodeController, 'Pincode', keyboardType: TextInputType.number),

                // THE FIELD THAT ACTUALLY FINDS THE HOUSE.
                //
                // House numbers are decorative across most of the delivery
                // area; a landmark and a turn are not. Written in whatever
                // words the customer uses, because a rider reads "hanuman
                // mandir ke piche 2 gali chhod ke green colour ki house" and
                // arrives, while a correctly formatted address with no
                // landmark leaves them phoning from the road.
                //
                // Multi-line and unformatted on purpose. Any attempt to
                // structure this would push people back into the boxes above,
                // which is the thing that does not work here.
                const SizedBox(height: 4),
                TextFormField(
                  controller: _directionsController,
                  maxLines: 3,
                  minLines: 2,
                  maxLength: 300,
                  textCapitalization: TextCapitalization.sentences,
                  decoration: const InputDecoration(
                    labelText: 'How to reach your house (optional)',
                    hintText:
                        'e.g. hanuman mandir ke piche, 2 gali chhod ke green '
                        'colour ka ghar',
                    helperText: 'Write it the way you would tell a friend. '
                        'The delivery worker sees this.',
                    helperMaxLines: 2,
                    alignLabelWithHint: true,
                  ),
                ),

                const SizedBox(height: 24),
                FilledButton(
                  onPressed: _isSaving ? null : _save,
                  child: _isSaving
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : Text(_isEditing ? 'Save Changes' : 'Save Address'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _field(TextEditingController controller, String label,
      {TextInputType? keyboardType, bool required = true}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        controller: controller,
        keyboardType: keyboardType,
        decoration: InputDecoration(labelText: label),
        validator: required
            ? (value) => (value == null || value.trim().isEmpty) ? '$label is required' : null
            : null,
      ),
    );
  }
}
