import styled from "styled-components";

export const Row = styled.div`
  display: flex;
  flex-direction: row;
  justify-content: flex-start;
  align-items: center;
  height: 32px;

  font-size: 14px;
  line-height: 17px;
  font-weight: normal;
  color: ${({ theme }) => theme.darkPrimaryColor};
  border: none;
`;

export const Header = styled(Row)`
  font-weight: 600;
  color: ${({ theme }) => theme.textColor};
  height: 17px;
  padding: 0;
`;

export const Cell = styled.div<{ flex?: number }>`
  flex: ${({ flex }) => flex || 1} 0 0;

  &:last-child {
    text-align: right;
  }
`;
