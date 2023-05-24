import { faChevronDown, faChevronRight } from "@fortawesome/free-solid-svg-icons";
import { FontAwesomeIcon } from "@fortawesome/react-fontawesome";
import { Disclosure } from "@headlessui/react";
import React from "react";
import { FormattedMessage, useIntl } from "react-intl";
import * as yup from "yup";

import { DeleteBlock } from "components/common/DeleteBlock";
import { UpdateConnectionDataResidency } from "components/connection/UpdateConnectionDataResidency";
import { Form } from "components/forms";
import { FormSubmissionButtons } from "components/forms/FormSubmissionButtons";
import { Button } from "components/ui/Button";
import { Card } from "components/ui/Card";
import { FlexContainer } from "components/ui/Flex";
import { Heading } from "components/ui/Heading";
import { Spinner } from "components/ui/Spinner";

import { Geography, WebBackendConnectionUpdate } from "core/request/AirbyteClient";
import { PageTrackingCodes, useTrackPage } from "core/services/analytics";
import { useAppMonitoringService } from "hooks/services/AppMonitoringService";
import { useConnectionEditService } from "hooks/services/ConnectionEdit/ConnectionEditService";
import { FeatureItem, useFeature } from "hooks/services/Feature";
import { useNotificationService } from "hooks/services/Notification";
import { useDeleteConnection } from "hooks/services/useConnectionHook";

import styles from "./ConnectionSettingsPage.module.scss";
import { SchemaUpdateNotifications } from "./SchemaUpdateNotifications";
import { StateBlock } from "./StateBlock";
import { UpdateConnectionName } from "./UpdateConnectionName";

export interface ConnectionSettingsFormValues {
  connectionName?: string;
  geography?: Geography;
  notifySchemaChanges?: boolean;
}

const connectionSettingsFormSchema = yup.object({
  connectionName: yup.string().min(1, "form.connectionName.minLength").optional(),
  geography: yup.mixed<Geography>().optional(),
  notifySchemaChanges: yup.bool().optional(),
});

export const ConnectionSettingsPage: React.FC = () => {
  const { connection, updateConnection } = useConnectionEditService();
  const { mutateAsync: deleteConnection } = useDeleteConnection();
  const canUpdateDataResidency = useFeature(FeatureItem.AllowChangeDataGeographies);
  const canSendSchemaUpdateNotifications = useFeature(FeatureItem.AllowAutoDetectSchema);
  const { registerNotification } = useNotificationService();
  const { formatMessage } = useIntl();
  const { trackError } = useAppMonitoringService();
  useTrackPage(PageTrackingCodes.CONNECTIONS_ITEM_SETTINGS);
  const onDelete = () => deleteConnection(connection);

  const onSuccess = () => {
    registerNotification({
      id: "connection_settings_change_success",
      text: formatMessage({ id: "form.changesSaved" }),
      type: "success",
    });
  };

  const onError = (e: Error, { connectionName }: ConnectionSettingsFormValues) => {
    trackError(e, { connectionName });
    registerNotification({
      id: "connection_settings_change_error",
      text: formatMessage({ id: "connection.updateFailed" }),
      type: "error",
    });
  };

  const connectionSettingsDefaultValues = () => {
    const defaultValues: ConnectionSettingsFormValues = {
      connectionName: connection.name,
    };
    if (canSendSchemaUpdateNotifications) {
      defaultValues.notifySchemaChanges = connection.notifySchemaChanges;
    }
    if (canUpdateDataResidency) {
      defaultValues.geography = connection.geography;
    }
    return defaultValues;
  };

  return (
    <div className={styles.container}>
      <FlexContainer direction="column" justifyContent="flex-start">
        <Card withPadding>
          <Heading as="h2" size="sm" className={styles.heading}>
            <FormattedMessage id="connectionForm.connectionSettings" />
          </Heading>
          <Form<ConnectionSettingsFormValues>
            trackDirtyChanges
            onSubmit={({ connectionName, geography, notifySchemaChanges }) => {
              const connectionUpdates: WebBackendConnectionUpdate = {
                name: connectionName,
                connectionId: connection.connectionId,
              };

              if (canUpdateDataResidency) {
                connectionUpdates.geography = geography;
              }

              if (canSendSchemaUpdateNotifications) {
                connectionUpdates.notifySchemaChanges = notifySchemaChanges;
              }

              return updateConnection(connectionUpdates);
            }}
            onError={onError}
            onSuccess={onSuccess}
            schema={connectionSettingsFormSchema}
            defaultValues={connectionSettingsDefaultValues()}
          >
            <UpdateConnectionName />
            {canSendSchemaUpdateNotifications && <SchemaUpdateNotifications />}
            {canUpdateDataResidency && <UpdateConnectionDataResidency />}
            <FormSubmissionButtons submitKey="form.saveChanges" />
          </Form>
        </Card>
        {connection.status !== "deprecated" && <DeleteBlock type="connection" onDelete={onDelete} />}
      </FlexContainer>
      <Disclosure>
        {({ open }) => (
          <>
            <Disclosure.Button
              as={Button}
              variant="clear"
              icon={<FontAwesomeIcon icon={open ? faChevronDown : faChevronRight} />}
              className={styles.advancedButton}
            >
              <FormattedMessage id="connectionForm.settings.advancedButton" />
            </Disclosure.Button>
            <Disclosure.Panel className={styles.advancedPanel}>
              <React.Suspense fallback={<Spinner />}>
                <StateBlock connectionId={connection.connectionId} />
              </React.Suspense>
            </Disclosure.Panel>
          </>
        )}
      </Disclosure>
    </div>
  );
};
