import React from "react";
import { FormattedMessage, useIntl } from "react-intl";
import { SchemaOf } from "yup";
import * as yup from "yup";

import { Form } from "components/forms";
import { FormSubmissionButtons } from "components/forms/FormSubmissionButtons";
import { CollapsibleCard } from "components/ui/CollapsibleCard";

import { DbtCloudJob, isSameJob, useDbtIntegration } from "core/api/cloud";
import { DbtCloudJobInfo } from "core/api/types/CloudApi";
import { useAppMonitoringService } from "hooks/services/AppMonitoringService";
import { useConnectionEditService } from "hooks/services/ConnectionEdit/ConnectionEditService";
import { useNotificationService } from "hooks/services/Notification";

import { DbtCloudTransformationsHookFormControls } from "./DbtCloudTransformationsHookFormControls";

export interface DbtCloudTransformationsHookFormValues {
  jobs: DbtCloudJob[];
}

const dbtJobsValidationSchema: SchemaOf<DbtCloudTransformationsHookFormValues> = yup.object({
  jobs: yup.array().of(
    yup.object().shape({
      jobId: yup.number().required("form.empty.error"),
      accountId: yup.number().required("form.empty.error"),
      operationId: yup.string().optional(),
      jobName: yup.string().optional(),
    })
  ),
});

interface DbtCloudTransformationsHookFormProps {
  availableDbtCloudJobs?: DbtCloudJobInfo[];
}

export const DbtCloudTransformationsHookForm: React.FC<DbtCloudTransformationsHookFormProps> = ({
  availableDbtCloudJobs = [],
}) => {
  const { formatMessage } = useIntl();
  const { registerNotification } = useNotificationService();
  const { trackError } = useAppMonitoringService();
  const { connection } = useConnectionEditService();
  const { hasDbtIntegration, saveJobs, dbtCloudJobs } = useDbtIntegration(connection);

  /**
   *  @author: Alex Birdsall
   *  because we don't store names for saved jobs, just the account and job IDs needed for
   *  webhook operation, we have to find the display names for saved jobs by comparing IDs
   *  with the list of available jobs as provided by dbt Cloud.
   */
  const jobs: DbtCloudJob[] = dbtCloudJobs.map((savedJob) => {
    const { jobName } = availableDbtCloudJobs.find((remoteJob) => isSameJob(remoteJob, savedJob)) || {};
    const { accountId, jobId } = savedJob;

    return { accountId, jobId, jobName };
  });

  const onSubmit = async ({ jobs }: DbtCloudTransformationsHookFormValues) => {
    await saveJobs(jobs);
  };

  const onSuccess = () => {
    registerNotification({
      id: "dbt_cloud_transformations_update_success",
      text: formatMessage({ id: "connection.dbtCloudJobs.updateSuccess" }),
      type: "success",
    });
  };

  const onError = (e: Error, { jobs }: DbtCloudTransformationsHookFormValues) => {
    trackError(e, { jobs });

    registerNotification({
      id: "dbt_cloud_transformations_update_error",
      text: formatMessage({
        id: "connection.dbtCloudJobs.updateError",
      }),
      type: "error",
    });
  };

  return (
    <Form<DbtCloudTransformationsHookFormValues>
      schema={dbtJobsValidationSchema}
      defaultValues={{ jobs: hasDbtIntegration ? jobs : dbtCloudJobs }}
      onSubmit={onSubmit}
      onSuccess={onSuccess}
      onError={onError}
      // TODO: uncomment when trackDirtyChanges will be fixed. Issue: https://github.com/airbytehq/airbyte/issues/28745
      // trackDirtyChanges
    >
      <CollapsibleCard title={<FormattedMessage id="connection.dbtCloudJobs.cardTitle" />} collapsible>
        <DbtCloudTransformationsHookFormControls
          availableDbtCloudJobs={availableDbtCloudJobs}
          hasDbtIntegration={hasDbtIntegration}
        />
        {hasDbtIntegration || jobs.length || dbtCloudJobs.length ? (
          <FormSubmissionButtons submitKey="form.saveChanges" />
        ) : null}
      </CollapsibleCard>
    </Form>
  );
};
