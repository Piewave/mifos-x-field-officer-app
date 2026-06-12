/*
 * Copyright 2024 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * See https://github.com/openMF/mifos-x-field-officer-app/blob/master/LICENSE.md
 */
package com.mifos.core.model.objects.account.loan

import com.mifos.core.model.utils.DateConstants
import com.mifos.core.model.utils.Parcelable
import com.mifos.core.model.utils.Parcelize
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Parcelize
@Serializable
data class LoanApproval(
    var approvedOnDate: String? = null,

    var approvedLoanAmount: Double? = null,

    var expectedDisbursementDate: String? = null,

    var note: String? = null,

    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    var locale: String = DateConstants.LOCALE,

    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    var dateFormat: String = DateConstants.DATE_FORMAT,
) : Parcelable
