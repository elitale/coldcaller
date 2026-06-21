package com.elitale.coldbirds.coldcalling.storage.repository;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface PhoneNumberRepository {

    record NewOwnedNumber(
            PhoneNumber number,
            Optional<String> friendlyName,
            AreaCode areaCode,
            String provider,
            NumberReputation reputation
    ) {
        public NewOwnedNumber {
            Objects.requireNonNull(number,       "number must not be null");
            Objects.requireNonNull(friendlyName, "friendlyName must not be null");
            Objects.requireNonNull(areaCode,     "areaCode must not be null");
            Objects.requireNonNull(provider,     "provider must not be null");
            Objects.requireNonNull(reputation,   "reputation must not be null");
            if (provider.isBlank()) throw new IllegalArgumentException("provider must not be blank");
        }
    }

    Result<OwnedNumber> save(NewOwnedNumber number);

    Result<OwnedNumber> update(OwnedNumber number);

    Optional<OwnedNumber> findById(PhoneNumberId id);

    Optional<OwnedNumber> findByNumber(PhoneNumber number);

    List<OwnedNumber> findAll();

    List<OwnedNumber> findAllActive();

    /** Hard delete — owned numbers are not soft-deleted. */
    Result<Void> delete(PhoneNumberId id);
}
