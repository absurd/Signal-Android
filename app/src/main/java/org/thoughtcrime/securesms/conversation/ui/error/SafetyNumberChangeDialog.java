package org.thoughtcrime.securesms.conversation.ui.error;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.telecom.Call;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.VerifyIdentityActivity;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.recipients.RecipientId;

import java.util.List;

public final class SafetyNumberChangeDialog extends DialogFragment implements SafetyNumberChangeAdapter.Callbacks {

  public static final String SAFETY_NUMBER_DIALOG = "SAFETY_NUMBER";

  private static final String RECIPIENT_IDS_EXTRA = "recipient_ids";
  private static final String MESSAGE_ID_EXTRA    = "message_id";
  private static final String MESSAGE_TYPE_EXTRA  = "message_type";
  private static final String IS_CALL_EXTRA       = "is_call";

  private SafetyNumberChangeViewModel viewModel;
  private SafetyNumberChangeAdapter   adapter;
  private View                        dialogView;

  public static void show(@NonNull FragmentManager fragmentManager, @NonNull List<IdentityDatabase.IdentityRecord> identityRecords) {
    List<String> ids = Stream.of(identityRecords)
                             .filterNot(IdentityDatabase.IdentityRecord::isFirstUse)
                             .map(record -> record.getRecipientId().serialize())
                             .distinct()
                             .toList();

    Bundle arguments = new Bundle();
    arguments.putStringArray(RECIPIENT_IDS_EXTRA, ids.toArray(new String[0]));
    SafetyNumberChangeDialog fragment = new SafetyNumberChangeDialog();
    fragment.setArguments(arguments);
    fragment.show(fragmentManager, SAFETY_NUMBER_DIALOG);
  }

  public static void show(@NonNull FragmentActivity fragmentActivity, @NonNull MessageRecord messageRecord) {
    List<String> ids = Stream.of(messageRecord.getIdentityKeyMismatches())
                             .map(mismatch -> mismatch.getRecipientId(fragmentActivity).serialize())
                             .distinct()
                             .toList();

    Bundle arguments = new Bundle();
    arguments.putStringArray(RECIPIENT_IDS_EXTRA, ids.toArray(new String[0]));
    arguments.putLong(MESSAGE_ID_EXTRA, messageRecord.getId());
    arguments.putString(MESSAGE_TYPE_EXTRA, messageRecord.isMms() ? MmsSmsDatabase.MMS_TRANSPORT : MmsSmsDatabase.SMS_TRANSPORT);
    SafetyNumberChangeDialog fragment = new SafetyNumberChangeDialog();
    fragment.setArguments(arguments);
    fragment.show(fragmentActivity.getSupportFragmentManager(), SAFETY_NUMBER_DIALOG);
  }

  public static void showForCall(@NonNull FragmentManager fragmentManager, @NonNull RecipientId recipientId) {
    Bundle arguments = new Bundle();
    arguments.putStringArray(RECIPIENT_IDS_EXTRA, new String[] { recipientId.serialize() });
    arguments.putBoolean(IS_CALL_EXTRA, true);
    SafetyNumberChangeDialog fragment = new SafetyNumberChangeDialog();
    fragment.setArguments(arguments);
    fragment.show(fragmentManager, SAFETY_NUMBER_DIALOG);
  }

  private SafetyNumberChangeDialog() { }

  @Override
  public @Nullable View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return dialogView;
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);

    //noinspection ConstantConditions
    List<RecipientId> recipientIds = Stream.of(getArguments().getStringArray(RECIPIENT_IDS_EXTRA)).map(RecipientId::from).toList();
    long              messageId    = getArguments().getLong(MESSAGE_ID_EXTRA, -1);
    String            messageType  = getArguments().getString(MESSAGE_TYPE_EXTRA, null);

    viewModel = ViewModelProviders.of(this, new SafetyNumberChangeViewModel.Factory(recipientIds, (messageId != -1) ? messageId : null, messageType)).get(SafetyNumberChangeViewModel.class);
    viewModel.getChangedRecipients().observe(getViewLifecycleOwner(), adapter::submitList);
  }

  @Override
  public @NonNull Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    boolean isCall = requireArguments().getBoolean(IS_CALL_EXTRA, false);

    dialogView = LayoutInflater.from(requireActivity()).inflate(R.layout.safety_number_change_dialog, null);

    AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity(), getTheme());

    configureView(dialogView);

    builder.setTitle(R.string.safety_number_change_dialog__safety_number_changes)
           .setView(dialogView)
           .setPositiveButton(isCall ? R.string.safety_number_change_dialog__call_anyway : R.string.safety_number_change_dialog__send_anyway, this::handleSendAnyway)
           .setNegativeButton(android.R.string.cancel, this::handleCancel);

    return builder.create();
  }

  @Override public void onDestroyView() {
    dialogView = null;
    super.onDestroyView();
  }

  private void configureView(View view) {
    RecyclerView list = view.findViewById(R.id.safety_number_change_dialog_list);
    adapter = new SafetyNumberChangeAdapter(this);
    list.setAdapter(adapter);
    list.setItemAnimator(null);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));
  }

  private void handleSendAnyway(DialogInterface dialogInterface, int which) {
    Activity activity = getActivity();
    Callback callback;
    if (activity instanceof Callback) {
      callback = (Callback) activity;
    } else {
      callback = null;
    }

    LiveData<TrustAndVerifyResult> trustOrVerifyResultLiveData = viewModel.trustOrVerifyChangedRecipients();

    Observer<TrustAndVerifyResult> observer = new Observer<TrustAndVerifyResult>() {
      @Override
      public void onChanged(TrustAndVerifyResult result) {
        if (callback != null) {
          switch (result) {
            case TRUST_AND_VERIFY:
              callback.onSendAnywayAfterSafetyNumberChange();
              break;
            case TRUST_VERIFY_AND_RESEND:
              callback.onMessageResentAfterSafetyNumberChange();
              break;
          }
        }
        trustOrVerifyResultLiveData.removeObserver(this);
      }
    };

    trustOrVerifyResultLiveData.observeForever(observer);
  }

  private void handleCancel(@NonNull DialogInterface dialogInterface, int which) {
    if (getActivity() instanceof Callback) {
      ((Callback) getActivity()).onCanceled();
    }
  }

  @Override
  public void onViewIdentityRecord(@NonNull IdentityDatabase.IdentityRecord identityRecord) {
    startActivity(VerifyIdentityActivity.newIntent(requireContext(), identityRecord));
  }

  public interface Callback {
    void onSendAnywayAfterSafetyNumberChange();
    void onMessageResentAfterSafetyNumberChange();
    void onCanceled();
  }
}
