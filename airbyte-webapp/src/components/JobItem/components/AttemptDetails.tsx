import classNames from "classnames";
import dayjs from "dayjs";
import React from "react";
import { FormattedMessage, useIntl } from "react-intl";

import Status from "core/statuses";

import { AttemptRead } from "../../../core/request/AirbyteClient";
import styles from "./AttemptDetails.module.scss";

interface AttemptDetailsProps {
  className?: string;
  attempt: AttemptRead;
  hasMultipleAttempts?: boolean;
}

const getFailureFromAttempt = (attempt: AttemptRead) => {
  return attempt.failureSummary && attempt.failureSummary.failures[0];
};

const AttemptDetails: React.FC<AttemptDetailsProps> = ({ attempt, className, hasMultipleAttempts }) => {
  const { formatMessage } = useIntl();

  if (attempt.status !== Status.SUCCEEDED && attempt.status !== Status.FAILED) {
    return null;
  }

  const formatBytes = (bytes?: number) => {
    if (!bytes) {
      return <FormattedMessage id="sources.countBytes" values={{ count: bytes || 0 }} />;
    }

    const k = 1024;
    const dm = 2;
    const sizes = ["Bytes", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    const result = parseFloat((bytes / Math.pow(k, i)).toFixed(dm));

    return <FormattedMessage id={`sources.count${sizes[i]}`} values={{ count: result }} />;
  };

  const getFailureOrigin = (attempt: AttemptRead) => {
    const failure = getFailureFromAttempt(attempt);
    const failureOrigin = failure?.failureOrigin ?? formatMessage({ id: "errorView.unknown" });

    return `${formatMessage({
      id: "sources.failureOrigin",
    })}: ${failureOrigin}`;
  };

  const getExternalFailureMessage = (attempt: AttemptRead) => {
    const failure = getFailureFromAttempt(attempt);
    const failureMessage = failure?.externalMessage ?? formatMessage({ id: "errorView.unknown" });

    return `${formatMessage({
      id: "sources.message",
    })}: ${failureMessage}`;
  };

  const date1 = dayjs(attempt.createdAt * 1000);
  const date2 = dayjs(attempt.updatedAt * 1000);
  const hours = Math.abs(date2.diff(date1, "hour"));
  const minutes = Math.abs(date2.diff(date1, "minute")) - hours * 60;
  const seconds = Math.abs(date2.diff(date1, "second")) - minutes * 60 - hours * 3600;
  const isFailed = attempt.status === Status.FAILED;

  return (
    <div className={classNames(styles.container, className)}>
      <div className={styles.details}>
        {hasMultipleAttempts && (
          <strong className={classNames(styles.lastAttempt, { [styles.failed]: isFailed })}>
            <FormattedMessage id="sources.lastAttempt" />
          </strong>
        )}
        <span>{formatBytes(attempt?.totalStats?.bytesEmitted)}</span>
        <span>
          <FormattedMessage
            id="sources.countEmittedRecords"
            values={{ count: attempt.totalStats?.recordsEmitted || 0 }}
          />
        </span>
        <span>
          <FormattedMessage
            id="sources.countCommittedRecords"
            values={{ count: attempt.totalStats?.recordsCommitted || 0 }}
          />
        </span>
        <span>
          {hours ? <FormattedMessage id="sources.hour" values={{ hour: hours }} /> : null}
          {hours || minutes ? <FormattedMessage id="sources.minute" values={{ minute: minutes }} /> : null}
          <FormattedMessage id="sources.second" values={{ second: seconds }} />
        </span>
      </div>
      {isFailed && (
        <div className={styles.failedMessage}>
          {formatMessage(
            {
              id: "ui.keyValuePairV3",
            },
            {
              key: getFailureOrigin(attempt),
              value: getExternalFailureMessage(attempt),
            }
          )}
        </div>
      )}
    </div>
  );
};

export default AttemptDetails;
