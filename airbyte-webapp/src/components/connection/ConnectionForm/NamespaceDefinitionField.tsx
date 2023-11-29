import { Field, FieldProps, useFormikContext } from "formik";
import { useCallback } from "react";
import { FormattedMessage } from "react-intl";

import { ControlLabels } from "components/LabeledControl";
import { Button } from "components/ui/Button";
import { FlexContainer } from "components/ui/Flex";
import { Text } from "components/ui/Text";
import { TextInputContainer } from "components/ui/TextInputContainer";

import { NamespaceDefinitionType } from "core/request/AirbyteClient";
import { useConnectionFormService } from "hooks/services/ConnectionForm/ConnectionFormService";
import { useModalService } from "hooks/services/Modal";

import { FormikConnectionFormValues } from "./formConfig";
import { FormFieldLayout } from "./FormFieldLayout";
import { HookFormConnectionFormValues } from "./hookFormConfig";
import { namespaceDefinitionOptions } from "./types";
import { DestinationNamespaceModal, DestinationNamespaceFormValues } from "../DestinationNamespaceModal";

/**
 * @deprecated the file will be removed in 3rd PR of the cleanup
 */
export const NamespaceDefinitionField = () => {
  const { mode } = useConnectionFormService();
  const { openModal, closeModal } = useModalService();

  const formikProps = useFormikContext<FormikConnectionFormValues>();

  const destinationNamespaceHookFormChange = useCallback(
    (value: DestinationNamespaceFormValues) => {
      formikProps.setFieldValue("namespaceDefinition", value.namespaceDefinition);

      if (value.namespaceDefinition === NamespaceDefinitionType.customformat) {
        formikProps.setFieldValue("namespaceFormat", value.namespaceFormat);
      }
    },
    [formikProps]
  );

  const openDestinationNamespaceModal = useCallback(
    () =>
      openModal({
        size: "lg",
        title: <FormattedMessage id="connectionForm.modal.destinationNamespace.title" />,
        content: () => (
          <DestinationNamespaceModal
            initialValues={{
              // just a stub to fix the type errors
              namespaceDefinition: formikProps.values
                .namespaceDefinition as HookFormConnectionFormValues["namespaceDefinition"],
              namespaceFormat: formikProps.values.namespaceFormat,
            }}
            onCloseModal={closeModal}
            onSubmit={destinationNamespaceHookFormChange}
          />
        ),
      }),
    [
      closeModal,
      destinationNamespaceHookFormChange,
      formikProps.values.namespaceDefinition,
      formikProps.values.namespaceFormat,
      openModal,
    ]
  );
  return (
    <Field name="namespaceDefinition">
      {({ field }: FieldProps<NamespaceDefinitionType>) => (
        <FormFieldLayout>
          <ControlLabels
            label={<FormattedMessage id="connectionForm.namespaceDefinition.title" />}
            infoTooltipContent={<FormattedMessage id="connectionForm.namespaceDefinition.subtitle" />}
          />
          <FlexContainer alignItems="center" justifyContent="space-between" gap="sm">
            <TextInputContainer disabled>
              <Text>
                <FormattedMessage id={`connectionForm.${namespaceDefinitionOptions[field.value]}`} />
              </Text>
            </TextInputContainer>
            <Button
              type="button"
              variant="secondary"
              disabled={mode === "readonly"}
              onClick={openDestinationNamespaceModal}
              data-testid="destination-namespace-edit-button"
            >
              <FormattedMessage id="form.edit" />
            </Button>
          </FlexContainer>
        </FormFieldLayout>
      )}
    </Field>
  );
};
